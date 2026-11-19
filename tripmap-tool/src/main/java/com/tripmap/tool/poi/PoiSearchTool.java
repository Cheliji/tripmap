package com.tripmap.tool.poi;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripmap.common.constant.PoiCategory;
import com.tripmap.common.constant.PoiTagVocab;
import com.tripmap.common.exception.BizException;
import com.tripmap.common.result.ResultCode;
import com.tripmap.dal.entity.PoiEntity;
import com.tripmap.dal.mapper.PoiMapper;
import com.tripmap.tool.spi.ToolNames;
import com.tripmap.tool.spi.TripTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * POI 检索工具（任务 2.2，设计说明书 §6.1 候选筛选第 1、2 步）。
 *
 * <p><b>流程</b>：SQL 硬过滤出候选池（单段，NULL=未知一律放行，2026-11 三态决策）
 * → 必去景点钉住校验 → {@link PoiRecallScorer} 内存打分 → 排序（必去恒在头部；
 * 平分 rating 降序 → id 升序，保证确定性）→ 截断 20～40。</p>
 *
 * <p><b>硬过滤口径</b>（TASKS 2.2.1）：{@code city} + {@code status=1}（正常）
 * + {@code (ticket_price IS NULL OR ≤ 预算)} + {@code (suggest_duration IS NULL OR ≤ 每日可用时间)}。
 * {@code open_hours} 不做检索条件（仅服务 3.5.3 闭馆预检、2.6.2 时间窗剪枝、3.8.1 硬校验#3）；
 * {@code tags} 不参与 SQL 过滤（JSON 收敛原则，方言中立考量，设计说明书 §8.1）。</p>
 *
 * <p><b>必去景点</b>：输入为 ID 列表（名称→ID 解析由上游节点负责）。
 * 通过硬过滤的必去项钉住在输出头部（不参与排序、不受截断影响）；
 * 与硬过滤冲突（闭园/下架/超预算/超时长/库中不存在）的进入
 * {@code mustVisitConflictIds} 交回上游走对话路径，不静默丢弃。</p>
 */
@Component
public class PoiSearchTool implements TripTool<PoiSearchInput, PoiSearchOutput> {

    private static final Logger log = LoggerFactory.getLogger(PoiSearchTool.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** poi.status：正常（硬过滤必须等于此值） */
    static final int STATUS_NORMAL = 1;

    private final PoiMapper poiMapper;
    private final PoiRecallScorer scorer;
    private final PoiSearchProperties properties;

    public PoiSearchTool(PoiMapper poiMapper, PoiRecallScorer scorer,
                         PoiSearchProperties properties) {
        this.poiMapper = poiMapper;
        this.scorer = scorer;
        this.properties = properties;
    }

    @Override
    public String name() {
        return ToolNames.POI_SEARCH;
    }

    @Override
    public PoiSearchOutput execute(PoiSearchInput input) {
        if (input == null || input.getCity() == null || input.getCity().isBlank()) {
            throw new BizException(ResultCode.PARAM_MISSING, "缺少必填参数: city");
        }

        // 1. SQL 硬过滤（NULL 放行）
        List<PoiEntity> pool = poiMapper.selectList(buildFilterWrapper(input));
        int poolSize = pool.size();

        // 2. 权重归一化（闭集校验 + 越界截断）
        Map<String, Double> tagWeights =
                scorer.normalizeWeights(input.getTagWeights(), PoiTagVocab.ALL, "标签");
        Map<String, Double> categoryWeights =
                scorer.normalizeWeights(input.getCategoryWeights(), PoiCategory.labels(), "分类");

        // 3. 必去景点：冲突检测（通过过滤的必去项必在候选池中，只需标记钉住）
        List<Long> mustIds = input.getMustVisitIds() == null ? List.of()
                : input.getMustVisitIds().stream().distinct().toList();
        List<Long> conflicts = List.of();
        if (!mustIds.isEmpty()) {
            Map<Long, PoiEntity> byId = poiMapper.selectBatchIds(mustIds).stream()
                    .collect(Collectors.toMap(PoiEntity::getId, e -> e, (a, b) -> a));
            List<Long> found = new ArrayList<>();
            for (Long id : mustIds) {
                PoiEntity entity = byId.get(id);
                if (entity == null || !passesHardFilter(entity, input)) {
                    found.add(id);
                }
            }
            conflicts = List.copyOf(found);
            if (!conflicts.isEmpty()) {
                log.warn("必去景点与硬过滤冲突 {} 个（闭园/超预算/超时长/不存在），交回上游对话: {}",
                        conflicts.size(), conflicts);
            }
        }
        Set<Long> mustVisitSet = new LinkedHashSet<>(mustIds);
        List<Long> conflictFinal = conflicts;

        // 4. 打分 + 映射
        List<PoiCandidate> scored = pool.stream()
                .map(entity -> {
                    PoiCandidate candidate = toCandidate(entity, mustVisitSet.contains(entity.getId()));
                    candidate.setScore(scorer.score(candidate, tagWeights, categoryWeights));
                    return candidate;
                })
                .toList();

        // 5. 排序：必去恒在头部；其余按 score 降序，平分 rating 降序（null 最后）→ id 升序
        Comparator<PoiCandidate> tieBreak = Comparator
                .comparingDouble(PoiCandidate::getScore).reversed()
                .thenComparing(Comparator.comparing(
                        (PoiCandidate c) -> c.getRating() == null
                                ? java.math.BigDecimal.valueOf(-1) : c.getRating(),
                        Comparator.reverseOrder()))
                .thenComparing(PoiCandidate::getId);
        List<PoiCandidate> sorted = scored.stream()
                .sorted(Comparator.comparing((PoiCandidate c) -> !c.isMustVisit()).thenComparing(tieBreak))
                .toList();

        // 6. 截断 + 池耗尽标记
        int limit = effectiveLimit(input.getLimit());
        List<PoiCandidate> candidates = sorted.size() > limit ? sorted.subList(0, limit) : sorted;

        return PoiSearchOutput.builder()
                .candidates(List.copyOf(candidates))
                .poolSize(poolSize)
                .poolExhausted(poolSize < properties.getRecallLimitMin())
                .mustVisitConflictIds(conflictFinal)
                .build();
    }

    /**
     * 构建硬过滤查询条件（单段 SQL，包级可见供单测断言 SQL 片段）。
     *
     * <p>NULL 三态（2026-11 决策，设计说明书 §8.1）：ticket_price / suggest_duration
     * 为 NULL（未知）的行一律放行，不参与对应上限比较；上限本身为 null（未设约束）
     * 时整个条件不下推。</p>
     */
    static LambdaQueryWrapper<PoiEntity> buildFilterWrapper(PoiSearchInput input) {
        LambdaQueryWrapper<PoiEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(PoiEntity::getCity, input.getCity())
                .eq(PoiEntity::getStatus, STATUS_NORMAL);
        if (input.getTicketPriceCap() != null) {
            wrapper.and(w -> w.isNull(PoiEntity::getTicketPrice)
                    .or().le(PoiEntity::getTicketPrice, input.getTicketPriceCap()));
        }
        if (input.getSuggestDurationCapMinutes() != null) {
            wrapper.and(w -> w.isNull(PoiEntity::getSuggestDuration)
                    .or().le(PoiEntity::getSuggestDuration, input.getSuggestDurationCapMinutes()));
        }
        return wrapper;
    }

    /**
     * 必去景点是否与硬过滤冲突（包级可见供单测）。
     * 与 {@link #buildFilterWrapper} 同口径：NULL 字段放行、上限 null 不约束。
     */
    static boolean passesHardFilter(PoiEntity entity, PoiSearchInput input) {
        if (!input.getCity().equals(entity.getCity())) {
            return false;
        }
        if (entity.getStatus() == null || entity.getStatus() != STATUS_NORMAL) {
            return false;
        }
        if (input.getTicketPriceCap() != null && entity.getTicketPrice() != null
                && entity.getTicketPrice().compareTo(input.getTicketPriceCap()) > 0) {
            return false;
        }
        if (input.getSuggestDurationCapMinutes() != null && entity.getSuggestDuration() != null
                && entity.getSuggestDuration() > input.getSuggestDurationCapMinutes()) {
            return false;
        }
        return true;
    }

    /** 截断上限收敛到配置区间 [recallLimitMin, recallLimitMax]；<=0 取默认值 */
    private int effectiveLimit(int requested) {
        int limit = requested <= 0 ? properties.getRecallLimitDefault() : requested;
        return Math.min(Math.max(limit, properties.getRecallLimitMin()),
                properties.getRecallLimitMax());
    }

    private PoiCandidate toCandidate(PoiEntity entity, boolean mustVisit) {
        return PoiCandidate.builder()
                .id(entity.getId())
                .name(entity.getName())
                .district(entity.getDistrict())
                .longitude(entity.getLongitude())
                .latitude(entity.getLatitude())
                .category(entity.getCategory())
                .tags(parseTags(entity))
                .rating(entity.getRating())
                .ticketPrice(entity.getTicketPrice())
                .suggestDuration(entity.getSuggestDuration())
                .openHours(entity.getOpenHours())
                .brief(entity.getBrief())
                .mustVisit(mustVisit)
                .build();
    }

    /** tags JSON → 字符串列表；解析失败按空列表处理并 warn（不因此中断召回） */
    private List<String> parseTags(PoiEntity entity) {
        String tagsJson = entity.getTags();
        if (tagsJson == null || tagsJson.isBlank()) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(tagsJson, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.warn("POI tags JSON 解析失败, id={}, 按无标签处理: {}", entity.getId(), tagsJson);
            return List.of();
        }
    }
}
