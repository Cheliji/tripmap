package com.tripmap.tool.commute;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通勤矩阵工具单测（任务 2.4.2/2.4.3/2.4.4）。
 *
 * <p>PoiMapper / AmapRouteClient / StringRedisTemplate 全部 Mockito 桩，
 * 覆盖：调用对数（无序对 n(n-1)/2）、矩阵对称性、缓存命中零 API、
 * 单对失败降级、孤立点/缺失率超阈值不可行、ID 不存在、key 规范化。</p>
 *
 * <p>按 AGENTS.md 定规：每个用例打印场景、输入与输出矩阵，console 可直接人工 review。</p>
 */
class CommuteMatrixToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PoiMapper poiMapper = mock(PoiMapper.class);
    private final AmapRouteClient routeClient = mock(AmapRouteClient.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final CommuteMatrixProperties properties = new CommuteMatrixProperties();
    private final CommuteMatrixTool tool = new CommuteMatrixTool(
            poiMapper, routeClient, redis, properties);

    // 三个测试 POI：西湖 / 灵隐寺 / 河坊街（坐标为杭州 GCJ-02 示意值）
    private final PoiEntity poiA = poi(101L, "西湖", "120.144700", "30.240900");
    private final PoiEntity poiB = poi(102L, "灵隐寺", "120.101700", "30.242300");
    private final PoiEntity poiC = poi(103L, "河坊街", "120.171600", "30.239200");

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
        // 缓存默认全未命中
        when(valueOps.multiGet(anyList())).thenAnswer(invocation -> {
            List<String> keys = invocation.getArgument(0);
            List<String> values = new ArrayList<>(keys.size());
            for (int i = 0; i < keys.size(); i++) {
                values.add(null);
            }
            return values;
        });
    }

    // ---------- 辅助 ----------

    private PoiEntity poi(Long id, String name, String lng, String lat) {
        return PoiEntity.builder()
                .id(id).name(name).city("杭州市")
                .longitude(new BigDecimal(lng)).latitude(new BigDecimal(lat))
                .build();
    }

    private void stubPois(PoiEntity... pois) {
        when(poiMapper.selectBatchIds(anyList())).thenReturn(List.of(pois));
    }

    /** 全部对返回固定耗时的默认桩 */
    private void stubRoute(long durationSeconds) {
        when(routeClient.query(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> new CommutePairMetric(durationSeconds, durationSeconds * 30));
    }

    private CommuteMatrixInput input(CommuteMode mode, Long... ids) {
        return CommuteMatrixInput.builder().mode(mode).poiIds(List.of(ids)).build();
    }

    private void printMatrix(String scenario, CommuteMatrixOutput out) {
        int n = out.getPoiIds().size();
        System.out.println("\n========== 场景：" + scenario + " ==========");
        System.out.printf("  输入: mode=%s, poiIds=%s%n", out.getMode(), out.getPoiIds());
        System.out.printf("  缓存命中 %d 对 | 高德调用 %d 对 | 缺失 %d 对 | 降级=%s%n",
                out.getCacheHitCount(), out.getApiCallCount(),
                out.getMissingPairs().size(), out.isDegraded() ? "是" : "否");
        long[][] m = out.getDurationsSeconds();
        for (int i = 0; i < n; i++) {
            StringBuilder row = new StringBuilder(String.format("  [%d] %-10s", i, out.getPoiIds().get(i)));
            for (int j = 0; j < n; j++) {
                row.append(String.format(" %-8s", m[i][j] == -1 ? "缺失" : m[i][j] + "s"));
            }
            System.out.println(row);
        }
    }

    // ---------- 基础矩阵行为（TASKS 2.4.4 对称性） ----------

    @Test
    void 三对POI只调三次API且矩阵对称() {
        stubPois(poiA, poiB, poiC);
        stubRoute(600L);

        CommuteMatrixOutput out = tool.execute(input(CommuteMode.BUS, 101L, 102L, 103L));
        printMatrix("3×3 矩阵全 API（调用对数与对称性）", out);

        verify(routeClient, times(3)).query(any(), any(), any(), any(), any(), any());
        assertEquals(3, out.getApiCallCount(), "3 点无序对 = 3 对，不是 6");
        assertEquals(0, out.getCacheHitCount());
        assertFalse(out.isDegraded());
        assertTrue(out.getMissingPairs().isEmpty());
        long[][] m = out.getDurationsSeconds();
        assertEquals(0L, m[0][0], "对角线为 0");
        assertEquals(600L, m[0][1]);
        assertEquals(m[0][1], m[1][0], "矩阵必须对称");
        assertEquals(m[0][2], m[2][0]);
        assertEquals(m[1][2], m[2][1]);
    }

    @Test
    void 驾车模式不传citycode公交必传() {
        stubPois(poiA, poiB, poiC);
        stubRoute(300L);

        tool.execute(input(CommuteMode.DRIVING, 101L, 102L, 103L));
        verify(routeClient, times(3)).query(eq(CommuteMode.DRIVING), eq(null),
                any(), any(), any(), any());

        tool.execute(input(CommuteMode.BUS, 101L, 102L, 103L));
        verify(routeClient, times(3)).query(eq(CommuteMode.BUS), eq("0571"),
                any(), any(), any(), any());
        System.out.println("\n========== 场景：出行方式参数化 ==========");
        System.out.println("  驾车 3 对: citycode=null（不需要）");
        System.out.println("  公交 3 对: citycode=0571（杭州，库中城市名=杭州市 自动归一）");
    }

    // ---------- Redis 缓存（TASKS 2.4.3） ----------

    @Test
    void 缓存命中对不调用API且key规范化() throws Exception {
        stubPois(poiA, poiB, poiC);
        // 3 对中 1 对命中缓存（600s），其余 2 对走 API
        CommutePairMetric cached = new CommutePairMetric(600L, 18000L);
        when(valueOps.multiGet(anyList())).thenAnswer(invocation -> {
            List<String> keys = invocation.getArgument(0);
            List<String> values = new ArrayList<>(keys.size());
            for (String key : keys) {
                values.add(key.endsWith("101:102") ? MAPPER.writeValueAsString(cached) : null);
            }
            return values;
        });
        stubRoute(900L);

        // 输入顺序 (103, 101, 102) → 矩阵行列 = [103, 101, 102]
        CommuteMatrixOutput out = tool.execute(input(CommuteMode.BUS, 103L, 101L, 102L));
        printMatrix("部分缓存命中（101:102 命中，其余 2 对走 API）", out);

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(valueOps).multiGet(keysCaptor.capture());
        List<String> keys = keysCaptor.getValue();
        assertEquals(3, keys.size());
        assertTrue(keys.contains("tripmap:commute:BUS:101:102"));
        assertTrue(keys.contains("tripmap:commute:BUS:101:103"), "key 与输入顺序无关，小 id 在前");
        assertTrue(keys.contains("tripmap:commute:BUS:102:103"));
        for (String key : keys) {
            // key 形态：tripmap:commute:BUS:小id:大id
            String[] parts = key.split(":");
            assertTrue(Long.parseLong(parts[3]) < Long.parseLong(parts[4]), "key 必须小 id 在前");
        }

        verify(routeClient, times(2)).query(any(), any(), any(), any(), any(), any());
        assertEquals(1, out.getCacheHitCount());
        assertEquals(2, out.getApiCallCount());
        // 矩阵行列顺序 = 输入去重后顺序 [103, 101, 102]
        assertEquals(List.of(103L, 101L, 102L), out.getPoiIds());
        assertEquals(600L, out.getDurationsSeconds()[1][2], "缓存对 101↔102 = 600s");
        assertEquals(900L, out.getDurationsSeconds()[0][1], "API 对 103↔101 = 900s");
        assertEquals(900L, out.getDurationsSeconds()[0][2], "API 对 103↔102 = 900s");
    }

    @Test
    void 全部缓存命中时零API调用() throws Exception {
        stubPois(poiA, poiB, poiC);
        CommutePairMetric cached = new CommutePairMetric(600L, 18000L);
        String json = MAPPER.writeValueAsString(cached);
        when(valueOps.multiGet(anyList())).thenAnswer(invocation -> List.of(json, json, json));

        CommuteMatrixOutput first = tool.execute(input(CommuteMode.BUS, 101L, 102L, 103L));
        printMatrix("全缓存命中（第一次执行）", first);

        verify(routeClient, never()).query(any(), any(), any(), any(), any(), any());
        assertEquals(3, first.getCacheHitCount());
        assertEquals(0, first.getApiCallCount(), "重复规划零 API 调用（TASKS 2.4.3）");
        assertFalse(first.isDegraded());
    }

    @Test
    void 成功项回写缓存失败项不回写() throws Exception {
        stubPois(poiA, poiB, poiC);
        stubRoute(600L);

        tool.execute(input(CommuteMode.BUS, 101L, 102L, 103L));

        verify(valueOps, times(3)).set(anyString(), anyString(), any(java.time.Duration.class));
        System.out.println("\n========== 场景：缓存回写 ==========");
        System.out.println("  3 对全部成功 → set 3 次（TTL 7 天）");
    }

    // ---------- 降级与缺失（TASKS 2.4.2） ----------

    @Test
    void 单对API失败降级为缺失其余对不受影响() {
        // 4 点簇：仅 101↔103 失败 → 缺失 1/6≈17% < 20% 阈值 → 降级但不抛异常
        PoiEntity poiD = poi(104L, "西溪湿地", "120.063700", "30.274100");
        stubPois(poiA, poiB, poiC, poiD);
        // 约定：routeClient.query(mode, cityCode, originLng, originLat, destLng, destLat)，
        // 工具对无序对 (i<j) 恒定以输入列表顺序发起（此处即 id 升序），
        // 故 originLng=参数2、destLng=参数4 可唯一定位每一对
        when(routeClient.query(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    String src = String.valueOf((Object) invocation.getArgument(2));
                    String dst = String.valueOf((Object) invocation.getArgument(4));
                    boolean pair101_103 = src.startsWith("120.1447") && dst.startsWith("120.1716");
                    if (pair101_103) {
                        throw new ExternalApiException(ResultCode.EXTERNAL_AMAP, "高德", true);
                    }
                    return new CommutePairMetric(600L, 18000L);
                });

        CommuteMatrixOutput out = tool.execute(input(CommuteMode.BUS, 101L, 102L, 103L, 104L));
        printMatrix("单对失败降级（101↔103 缺失，其余正常）", out);

        assertTrue(out.isDegraded());
        assertEquals(1, out.getMissingPairs().size());
        assertEquals("101→103", out.getMissingPairs().get(0));
        assertEquals(-1L, out.getDurationsSeconds()[0][2], "失败对为 -1");
        assertEquals(600L, out.getDurationsSeconds()[0][1], "正常对不受影响");
        assertEquals(600L, out.getDurationsSeconds()[1][2]);
        // 6 对全调了 API（失败发生在调用层）
        assertEquals(6, out.getApiCallCount());
    }

    @Test
    void 高德返回空方案记缺失不抛异常() {
        stubPois(poiA, poiB, poiC);
        when(routeClient.query(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> new CommutePairMetric(null, null));

        // 3 点全不可达 → 全员孤立 → 300002
        BizException e = assertThrows(BizException.class,
                () -> tool.execute(input(CommuteMode.BUS, 101L, 102L, 103L)));
        System.out.println("\n========== 场景：全部不可达 → 孤立点 ==========");
        System.out.printf("  实际抛出: code=%d, message=%s%n", e.getCode(), e.getMessage());
        assertEquals(300002, e.getCode());
    }

    // ---------- 可行性（孤立点 / 缺失率阈值） ----------

    @Test
    void 孤立点抛规划不可行() {
        stubPois(poiA, poiB, poiC);
        // 103（河坊街）与任何点都不可达
        when(routeClient.query(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    boolean involvesC = String.valueOf((Object) invocation.getArgument(2)).startsWith("120.1716")
                            || String.valueOf((Object) invocation.getArgument(4)).startsWith("120.1716");
                    return involvesC ? new CommutePairMetric(null, null)
                            : new CommutePairMetric(600L, 18000L);
                });

        BizException e = assertThrows(BizException.class,
                () -> tool.execute(input(CommuteMode.BUS, 101L, 102L, 103L)));
        System.out.println("\n========== 场景：孤立点（103 与所有点不可达）→ 300002 ==========");
        System.out.printf("  实际抛出: code=%d, message=%s%n", e.getCode(), e.getMessage());
        assertEquals(300002, e.getCode());
        assertTrue(e.getMessage().contains("103"));
    }

    @Test
    void 缺失率低于阈值仅标记降级() {
        // 4 点 6 对（输入顺序 101,102,103,104 → 对：(101,102)(101,103)(101,104)(102,103)(102,104)(103,104)）
        PoiEntity poiD = poi(104L, "西溪湿地", "120.063700", "30.274100");
        stubPois(poiA, poiB, poiC, poiD);
        // 仅 101↔103 不可达 → 缺失 1/6≈16.7% < 20% → 仅降级不抛异常
        when(routeClient.query(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    String src = String.valueOf((Object) invocation.getArgument(2));
                    String dst = String.valueOf((Object) invocation.getArgument(4));
                    boolean pair101_103 = src.startsWith("120.1447") && dst.startsWith("120.1716");
                    return pair101_103 ? new CommutePairMetric(null, null)
                            : new CommutePairMetric(600L, 18000L);
                });

        CommuteMatrixOutput out = tool.execute(
                input(CommuteMode.BUS, 101L, 102L, 103L, 104L));
        System.out.println("\n========== 场景：缺失率 1/6≈17% < 阈值 20% → 仅降级 ==========");
        System.out.printf("  缺失对=%s, degraded=%s%n", out.getMissingPairs(), out.isDegraded());
        assertTrue(out.isDegraded());
        assertEquals(1, out.getMissingPairs().size());
        assertEquals(-1L, out.getDurationsSeconds()[0][2]);
    }

    @Test
    void 缺失率超阈值抛规划不可行() {
        PoiEntity poiD = poi(104L, "西溪湿地", "120.063700", "30.274100");
        stubPois(poiA, poiB, poiC, poiD);
        // 断 101↔103 与 102↔104 → 缺失 2/6≈33% > 20% → 300002
        when(routeClient.query(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    String src = String.valueOf((Object) invocation.getArgument(2));
                    String dst = String.valueOf((Object) invocation.getArgument(4));
                    boolean broken = (src.startsWith("120.1447") && dst.startsWith("120.1716"))
                            || (src.startsWith("120.1017") && dst.startsWith("120.0637"));
                    return broken ? new CommutePairMetric(null, null)
                            : new CommutePairMetric(600L, 18000L);
                });
        BizException e = assertThrows(BizException.class,
                () -> tool.execute(input(CommuteMode.BUS, 101L, 102L, 103L, 104L)));
        System.out.println("\n========== 场景：缺失率 2/6≈33% > 阈值 20% → 300002 ==========");
        System.out.printf("  实际抛出: code=%d, message=%s%n", e.getCode(), e.getMessage());
        assertEquals(300002, e.getCode());
    }

    // ---------- 参数校验 ----------

    @Test
    void POI不存在抛BizException() {
        when(poiMapper.selectBatchIds(anyList())).thenReturn(List.of(poiA));
        BizException e = assertThrows(BizException.class,
                () -> tool.execute(input(CommuteMode.BUS, 101L, 999L)));
        System.out.println("\n========== 场景：POI 999 不存在 → BizException ==========");
        System.out.printf("  实际抛出: code=%d, message=%s%n", e.getCode(), e.getMessage());
        assertEquals(100002, e.getCode());
    }

    @Test
    void 少于两个不同POI抛BizException() {
        BizException e = assertThrows(BizException.class,
                () -> tool.execute(input(CommuteMode.BUS, 101L, 101L)));
        System.out.println("\n========== 场景：去重后只剩 1 个 POI → BizException(100001) ==========");
        System.out.printf("  实际抛出: code=%d, message=%s%n", e.getCode(), e.getMessage());
        assertEquals(100001, e.getCode());
    }

    @Test
    void 缺少出行方式抛BizException() {
        BizException e = assertThrows(BizException.class, () -> tool.execute(
                CommuteMatrixInput.builder().poiIds(List.of(101L, 102L)).build()));
        System.out.println("\n========== 场景：mode=null → BizException(100002) ==========");
        System.out.printf("  实际抛出: code=%d, message=%s%n", e.getCode(), e.getMessage());
        assertEquals(100002, e.getCode());
    }

    @Test
    void 未配置citycode的城市公交不可用() {
        PoiEntity beijing = poi(201L, "故宫", "116.397000", "39.916000");
        beijing.setCity("北京市");
        stubPois(poiA, beijing);
        stubRoute(600L);

        BizException e = assertThrows(BizException.class,
                () -> tool.execute(input(CommuteMode.BUS, 101L, 201L)));
        System.out.println("\n========== 场景：北京市未配置 citycode → BizException ==========");
        System.out.printf("  实际抛出: code=%d, message=%s%n", e.getCode(), e.getMessage());

        // 驾车不依赖 citycode，正常
        CommuteMatrixOutput out = tool.execute(input(CommuteMode.DRIVING, 101L, 201L));
        assertFalse(out.isDegraded());
        System.out.println("  同两 POI 驾车模式: 正常出矩阵（驾车不依赖 citycode）");
    }
}
