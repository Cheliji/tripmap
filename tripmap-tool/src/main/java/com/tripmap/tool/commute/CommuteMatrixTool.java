package com.tripmap.tool.commute;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripmap.common.exception.BizException;
import com.tripmap.common.exception.ExternalApiException;
import com.tripmap.common.result.ResultCode;
import com.tripmap.dal.entity.PoiEntity;
import com.tripmap.dal.mapper.PoiMapper;
import com.tripmap.tool.commute.client.AmapRouteClient;
import com.tripmap.tool.commute.config.CommuteMatrixProperties;
import com.tripmap.tool.commute.dto.CommuteMatrixInput;
import com.tripmap.tool.commute.dto.CommuteMatrixOutput;
import com.tripmap.tool.commute.dto.CommutePairMetric;
import com.tripmap.tool.spi.ToolNames;
import com.tripmap.tool.spi.TripTool;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 通勤矩阵工具（任务 2.4，设计说明书 §9 工具层：外部 API（高德），
 * 输入 POI ID 列表 + 出行方式，输出两两通勤时间矩阵）。
 *
 * <p><b>流程</b>：参数校验 → ID 批量查库取坐标（GCJ-02）→ Redis 批量查缓存
 * （key 规范化 {@code 小id:大id}，对称去重，TTL 7 天，设计说明书 §8.3）→
 * 未命中无序对 (i&lt;j) {@link CompletableFuture} 并行调高德（总闸是
 * {@code AmapClient} 的 QPS 令牌桶）→ 成功项回写缓存 → 聚合对称矩阵。</p>
 *
 * <p><b>降级策略</b>（设计说明书 §6.2"交由通勤矩阵校验暴露"）：</p>
 * <ul>
 *   <li>单对 API 失败（重试耗尽）或高德返回空方案 → 该项记缺失（-1），
 *       输出 {@code missingPairs} 明文列出，不拖垮整矩阵；</li>
 *   <li>出现孤立点（与簇内所有点不可达）或缺失率超阈值（配置化，默认 20%）
 *       → 该簇规划不可行，抛 {@link BizException}（300002），
 *       交回上游对话环节（对接 §7.3 回退路由）；</li>
 *   <li>禁止用直线距离/估算值填充缺失（AGENTS.md 原则 1：几何正确性
 *       只来自算法与外部 API）。</li>
 * </ul>
 *
 * <p><b>对称近似</b>：通勤方向差异在市内景点间可忽略，矩阵按无序对计算
 * 后镜像填充，省一半配额；单测验证对称性（TASKS 2.4.4）。</p>
 */
@Component
public class CommuteMatrixTool implements TripTool<CommuteMatrixInput, CommuteMatrixOutput> {

    private static final Logger log = LoggerFactory.getLogger(CommuteMatrixTool.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 矩阵缺失值标记（{@link CommuteMatrixOutput#getDurationsSeconds()}） */
    static final long MISSING = -1L;

    private final PoiMapper poiMapper;
    private final AmapRouteClient routeClient;
    private final StringRedisTemplate redisTemplate;
    private final CommuteMatrixProperties properties;
    private final ExecutorService executor;

    public CommuteMatrixTool(PoiMapper poiMapper, AmapRouteClient routeClient,
                             StringRedisTemplate redisTemplate,
                             CommuteMatrixProperties properties) {
        this.poiMapper = poiMapper;
        this.routeClient = routeClient;
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        // TODO 线程池创建有问题
        this.executor = Executors.newFixedThreadPool(properties.getMaxConcurrency(),
                namedDaemonFactory("commute-matrix-"));
    }

    /** 应用关闭时回收并行线程池（工具为单例 Bean，池随 Bean 生命周期） */
    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    @Override
    public String name() {
        return ToolNames.COMMUTE_MATRIX;
    }

    @Override
    public CommuteMatrixOutput execute(CommuteMatrixInput input) {
        // 1. 参数校验 + ID 去重保序
        List<Long> poiIds = validateAndDistinct(input);
        int n = poiIds.size();

        // 2. ID 批量查库取坐标
        List<PoiEntity> pois = loadPois(poiIds);
        String cityCode = resolveCityCode(pois, input.getMode());

        // 3. 生成无序对 (i<j)，缓存批量查询
        List<Pair> pairs = buildPairs(poiIds);
        Map<String, Pair> keyToPair = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>(pairs.size());
        for (Pair pair : pairs) {
            String key = cacheKey(input.getMode(), pair.a, pair.b);
            keyToPair.put(key, pair);
            keys.add(key);
        }
        List<CommutePairMetric> cached = batchReadCache(keys);

        // 4. 命中填矩阵；未命中对并行调高德
        // 初始化：对角线 0，其余 -1（缺失）；Java 数组默认 0，必须显式铺满 MISSING
        long[][] matrix = new long[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                matrix[i][j] = i == j ? 0L : MISSING;
            }
        }
        List<Pair> misses = new ArrayList<>();
        int cacheHits = 0;
        for (int i = 0; i < pairs.size(); i++) {
            Pair pair = pairs.get(i);
            // cached[i] 已反序列化（null=未命中；duration=null 视为历史不可达占位，不当命中）
            CommutePairMetric metric = i < cached.size() ? cached.get(i) : null;
            if (metric != null && metric.getDurationSeconds() != null) {
                fill(matrix, pair, metric.getDurationSeconds());
                cacheHits++;
            } else {
                if (metric != null) {
                    log.warn("通勤缓存存在不可达占位值，按未命中处理: key={}", keys.get(i));
                }
                misses.add(pair);
            }
        }

        int apiCalls = 0;
        if (!misses.isEmpty()) {
            apiCalls = fetchAndFill(input.getMode(), cityCode, pois, matrix, misses);
        }

        // 5. 聚合输出 + 缺失处理
        List<String> missingPairs = collectMissingPairs(poiIds, matrix);
        boolean degraded = !missingPairs.isEmpty();
        checkFeasibility(poiIds, matrix, missingPairs, degraded);

        CommuteMatrixOutput output = CommuteMatrixOutput.builder()
                .poiIds(poiIds)
                .durationsSeconds(matrix)
                .missingPairs(missingPairs)
                .mode(input.getMode())
                .apiCallCount(apiCalls)
                .cacheHitCount(cacheHits)
                .degraded(degraded)
                .build();
        printMatrix(output);
        return output;
    }

    // ---------- 1. 参数校验 ----------

    private List<Long> validateAndDistinct(CommuteMatrixInput input) {
        if (input == null) {
            throw new BizException(ResultCode.PARAM_MISSING, "缺少必填参数: input");
        }
        if (input.getPoiIds() == null || input.getPoiIds().isEmpty()) {
            throw new BizException(ResultCode.PARAM_MISSING, "缺少必填参数: poiIds");
        }
        if (input.getPoiIds().stream().anyMatch(Objects::isNull)) {
            throw new BizException(ResultCode.PARAM_ILLEGAL, "poiIds 不允许包含 null");
        }
        if (input.getMode() == null) {
            throw new BizException(ResultCode.PARAM_MISSING, "缺少必填参数: mode");
        }
        List<Long> distinct = input.getPoiIds().stream().distinct().toList();
        if (distinct.size() < 2) {
            throw new BizException(ResultCode.PARAM_VALIDATION_FAILED,
                    "通勤矩阵至少需要 2 个不同的 POI（实际 " + distinct.size() + " 个）");
        }
        return distinct;
    }

    // ---------- 2. 查库取坐标 ----------

    private List<PoiEntity> loadPois(List<Long> poiIds) {
        Map<Long, PoiEntity> byId = new LinkedHashMap<>();
        for (PoiEntity entity : poiMapper.selectBatchIds(poiIds)) {
            byId.put(entity.getId(), entity);
        }
        List<Long> missingIds = poiIds.stream().filter(id -> !byId.containsKey(id)).toList();
        if (!missingIds.isEmpty()) {
            throw new BizException(ResultCode.PARAM_MISSING, "POI 在库中不存在: " + missingIds);
        }
        List<Long> noCoordIds = poiIds.stream()
                .filter(id -> byId.get(id).getLongitude() == null || byId.get(id).getLatitude() == null)
                .toList();
        if (!noCoordIds.isEmpty()) {
            throw new BizException(ResultCode.PARAM_ILLEGAL, "POI 坐标缺失，无法规划通勤: " + noCoordIds);
        }
        return poiIds.stream().map(byId::get).toList();
    }

    /**
     * 仅公交需要 citycode（驾车不限城市）：逐个 POI 解析，任一未配置按城市不支持处理；
     * 出现多种 citycode（跨城簇）MVP 不支持，明确拒绝（单城市口径，设计说明书 §8.1）。
     */
    private String resolveCityCode(List<PoiEntity> pois, CommuteMode mode) {
        if (mode != CommuteMode.BUS) {
            return null;
        }
        Map<String, String> codeToCity = new LinkedHashMap<>();
        for (PoiEntity poi : pois) {
            String code = properties.resolveCityCode(poi.getCity());
            if (code == null) {
                throw new BizException(ResultCode.CITY_NOT_SUPPORTED.getCode(),
                        "城市未配置高德 citycode，公交通勤不可用: " + poi.getCity());
            }
            codeToCity.put(code, poi.getCity());
        }
        if (codeToCity.size() > 1) {
            throw new BizException(ResultCode.PARAM_VALIDATION_FAILED.getCode(),
                    "通勤矩阵不支持跨城簇（MVP 单城市），涉及城市: " + codeToCity.values());
        }
        return codeToCity.keySet().iterator().next();
    }

    // ---------- 3/4. 缓存与并行抓取 ----------

    /** 无序对列表 (i<j)，n 个点共 n(n-1)/2 对 */
    private List<Pair> buildPairs(List<Long> poiIds) {
        List<Pair> pairs = new ArrayList<>();
        for (int i = 0; i < poiIds.size(); i++) {
            for (int j = i + 1; j < poiIds.size(); j++) {
                pairs.add(new Pair(i, j, poiIds.get(i), poiIds.get(j)));
            }
        }
        return pairs;
    }

    private String cacheKey(CommuteMode mode, long idA, long idB) {
        // id 排序规范化：小id在前，与对称矩阵口径一致，a→b 与 b→a 同 key
        long small = Math.min(idA, idB);
        long large = Math.max(idA, idB);
        return properties.getCacheKeyPrefix() + mode.name() + ":" + small + ":" + large;
    }

    private List<CommutePairMetric> batchReadCache(List<String> keys) {
        try {
            List<String> jsonList = redisTemplate.opsForValue().multiGet(keys);
            if (jsonList == null) {
                return new ArrayList<>(keys.size());
            }
            return jsonList.stream().map(json -> {
                if (json == null) {
                    return (CommutePairMetric) null;
                }
                try {
                    return OBJECT_MAPPER.readValue(json, CommutePairMetric.class);
                } catch (JsonProcessingException e) {
                    log.warn("通勤缓存反序列化失败，视为未命中", e);
                    return null;
                }
            }).toList();
        } catch (Exception e) {
            // 缓存不可用不阻塞主流程（退化为全部走 API，令牌桶兜底 QPS）
            log.warn("通勤缓存批量读取失败，按全部未命中处理", e);
            List<CommutePairMetric> fallbacks = new ArrayList<>(keys.size());
            for (int i = 0; i < keys.size(); i++) {
                fallbacks.add(null);
            }
            return fallbacks;
        }
    }

    /**
     * 未命中对并行调高德并填矩阵：单对失败（重试耗尽）记缺失降级，
     * 不拖垮整矩阵；成功项回写缓存（不可达/失败项不缓存，下次自动重查）。
     *
     * @return 实际完成的高德调用对数
     */
    private int fetchAndFill(CommuteMode mode, String cityCode, List<PoiEntity> pois,
                             long[][] matrix, List<Pair> misses) {
        List<CompletableFuture<PairResult>> futures = new ArrayList<>(misses.size());
        for (Pair pair : misses) {
            PoiEntity a = pois.get(pair.indexA);
            PoiEntity b = pois.get(pair.indexB);
            futures.add(CompletableFuture.supplyAsync(
                    () -> querySafely(mode, cityCode, a, b, pair), executor));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        int apiCalls = 0;
        for (CompletableFuture<PairResult> future : futures) {
            PairResult result = future.join();
            apiCalls++;
            if (result.metric() != null && result.metric().getDurationSeconds() != null) {
                fill(matrix, result.pair(), result.metric().getDurationSeconds());
                writeCache(cacheKey(mode, result.pair().a, result.pair().b), result.metric());
            }
            // 失败/不可达：保持 -1，已记入 missingPairs
        }
        return apiCalls;
    }

    /** 单对查询的异常隔离：可重试错误耗尽、不可达均降级为 null，不向上抛 */
    private PairResult querySafely(CommuteMode mode, String cityCode,
                                   PoiEntity a, PoiEntity b, Pair pair) {
        try {
            CommutePairMetric metric = routeClient.query(mode, cityCode,
                    a.getLongitude(), a.getLatitude(), b.getLongitude(), b.getLatitude());
            if (metric.getDurationSeconds() == null) {
                log.warn("通勤不可达: {}({}) → {}({}), mode={}",
                        a.getName(), a.getId(), b.getName(), b.getId(), mode);
            }
            return new PairResult(pair, metric);
        } catch (ExternalApiException e) {
            // 单项降级（设计说明书 §6.2）：retryable 已由 AmapClient 退避重试耗尽
            log.warn("通勤查询失败降级: {}({}) → {}({}), mode={}, retryable={}",
                    a.getName(), a.getId(), b.getName(), b.getId(), mode, e.isRetryable());
            return new PairResult(pair, null);
        } catch (Exception e) {
            // 确定性代码不应走到这里；防御性降级 + error 日志，不静默吞
            log.error("通勤查询未预期异常: {} → {}, mode={}", a.getId(), b.getId(), mode, e);
            return new PairResult(pair, null);
        }
    }

    private void writeCache(String key, CommutePairMetric metric) {
        try {
            redisTemplate.opsForValue().set(key, OBJECT_MAPPER.writeValueAsString(metric),
                    Duration.ofDays(properties.getCacheTtlDays()));
        } catch (Exception e) {
            // 缓存写失败不影响主流程（仅下次多一次 API 调用，令牌桶兜底）
            log.warn("通勤缓存写入失败: key={}", key, e);
        }
    }

    // ---------- 5. 缺失聚合与可行性 ----------

    private void fill(long[][] matrix, Pair pair, long durationSeconds) {
        matrix[pair.indexA()][pair.indexB()] = durationSeconds;
        matrix[pair.indexB()][pair.indexA()] = durationSeconds;
    }

    /** 缺失对明文列表（"idA→idB"，idA&lt;idB 去重，每对只列一次） */
    private List<String> collectMissingPairs(List<Long> poiIds, long[][] matrix) {
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < matrix.length; i++) {
            for (int j = i + 1; j < matrix.length; j++) {
                if (matrix[i][j] == MISSING) {
                    missing.add(poiIds.get(i) + "→" + poiIds.get(j));
                }
            }
        }
        return missing;
    }

    /**
     * 可行性检查：孤立点（与簇内所有点不可达）或缺失率超阈值 →
     * 抛 300002 规划不可行，交回上游对话/回退路由（设计说明书 §7.3）。
     * 阈值口径：缺失对数 / 总对数（n(n-1)/2）。
     */
    private void checkFeasibility(List<Long> poiIds, long[][] matrix,
                                  List<String> missingPairs, boolean degraded) {
        if (!degraded) {
            return;
        }
        int totalPairs = matrix.length * (matrix.length - 1) / 2;
        double missingRatio = (double) missingPairs.size() / totalPairs;

        List<Long> isolated = new ArrayList<>();
        for (int i = 0; i < matrix.length; i++) {
            boolean allMissing = true;
            for (int j = 0; j < matrix.length; j++) {
                if (i != j && matrix[i][j] != MISSING) {
                    allMissing = false;
                    break;
                }
            }
            if (allMissing) {
                isolated.add(poiIds.get(i));
            }
        }

        if (!isolated.isEmpty()) {
            throw new BizException(ResultCode.PLAN_INFEASIBLE.getCode(),
                    "以下景点与簇内其他景点均不通勤（孤立点），请调整行程: " + isolated);
        }
        if (missingRatio > properties.getMissingRatioThreshold()) {
            throw new BizException(ResultCode.PLAN_INFEASIBLE.getCode(),
                    String.format("通勤数据缺失率 %.0f%% 超过阈值 %.0f%%（缺失 %d/%d 对）"
                                    + "，请调整出行方式或景点组合后重试",
                            missingRatio * 100, properties.getMissingRatioThreshold() * 100,
                            missingPairs.size(), totalPairs));
        }
        log.warn("通勤矩阵存在缺失但可用: {} 对缺失，缺失率 {}%（阈值 {}%），评测节点可见",
                missingPairs.size(), String.format("%.1f", missingRatio * 100),
                properties.getMissingRatioThreshold() * 100);
    }

    // ---------- 日志（人读表格，AGENTS.md 测试/日志定规） ----------

    private void printMatrix(CommuteMatrixOutput output) {
        StringBuilder sb = new StringBuilder("\n---------- 通勤矩阵 mode=")
                .append(output.getMode()).append(" ----------\n");
        sb.append(String.format("  命中缓存 %d 对，调用高德 %d 对，缺失 %d 对，降级=%s%n",
                output.getCacheHitCount(), output.getApiCallCount(),
                output.getMissingPairs().size(), output.isDegraded() ? "是" : "否"));
        List<Long> ids = output.getPoiIds();
        long[][] m = output.getDurationsSeconds();
        for (int i = 0; i < ids.size(); i++) {
            for (int j = i + 1; j < ids.size(); j++) {
                sb.append(String.format("  %d→%d = %s%n", ids.get(i), ids.get(j),
                        m[i][j] == MISSING ? "缺失" : String.format("%.1f 分钟", m[i][j] / 60.0)));
            }
        }
        log.info(sb.toString());
    }

    private static ThreadFactory namedDaemonFactory(String prefix) {
        AtomicInteger seq = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + seq.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }

    /** 无序对（i<j 的矩阵坐标 + POI ID） */
    record Pair(int indexA, int indexB, long a, long b) {
    }

    /** 单对查询结果（metric=null 表示降级/不可达） */
    private record PairResult(Pair pair, CommutePairMetric metric) {
    }
}
