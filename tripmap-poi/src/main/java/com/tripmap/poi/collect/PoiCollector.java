package com.tripmap.poi.collect;

import com.tripmap.common.exception.ExternalApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;

/**
 * POI 采集执行器（任务 1.3.2，设计说明书 §3.3）
 *
 * <p>把城市采集矩阵展开为（分区 × 分类）组合逐格拉取，并承担两项采集侧职责：</p>
 * <ul>
 *   <li><b>容错</b>：单个组合查询失败仅记 warn 跳过，不中断整体采集（§3.3 容错条款）；</li>
 *   <li><b>去重</b>：以高德 {@code id} 为天然主键，跨组合重复的 POI 只保留一条，
 *       字段值以最新一次采集为准（§3.5 幂等可重跑），并记录其命中的多个关键词/编码（§3.3 去重条款）。</li>
 * </ul>
 *
 * <p>纯 POJO 零 Spring；组合间最小调用间隔由调用方注入（§3.3 建议 sleep 100ms）。</p>
 */
public class PoiCollector {

    private static final Logger log = LoggerFactory.getLogger(PoiCollector.class);

    private final AmapPoiSearcher searcher;

    public PoiCollector(AmapPoiSearcher searcher) {
        this.searcher = searcher;
    }

    /**
     * 执行整城采集。
     *
     * @param matrix          城市采集矩阵（1.3.1）
     * @param comboIntervalMs 组合间最小调用间隔（§3.3 建议 100ms；单测传 0）
     * @return 采集结果（去重后 POI + 查询统计）
     */
    public CollectResult collect(CityCollectMatrix matrix, long comboIntervalMs) {
        Map<String, CollectedPoi> merged = new LinkedHashMap<>();
        List<CityCollectMatrix.Combo> combos = matrix.combos();
        int failedQueries = 0;
        long startNanos = System.nanoTime();

        for (int i = 0; i < combos.size(); i++) {
            CityCollectMatrix.Combo combo = combos.get(i);
            AmapPoiSearchQuery query = combo.toQuery();
            try {
                List<RawPoi> pois = searcher.search(query);
                for (RawPoi poi : pois) {
                    // §3.3：amap_id 去重；字段以最新采集为准；命中关键词集合式去重合并
                    CollectedPoi collected = merged.computeIfAbsent(poi.getAmapId(),
                            id -> new CollectedPoi(poi));
                    collected.poi = poi;
                    collected.hitKeywords.add(combo.hitTag());
                }
                log.debug("组合采集完成 ({}/{}): 分区={} 分类={} 命中={} 条",
                        i + 1, combos.size(), combo.district().name(),
                        combo.category().label(), pois.size());
            } catch (ExternalApiException e) {
                // §3.3 容错条款：单组合失败仅记日志跳过，不中断整体采集
                failedQueries++;
                log.warn("组合查询失败，跳过 ({}/{}): 分区={} 分类={} 原因={}",
                        i + 1, combos.size(), combo.district().name(),
                        combo.category().label(), e.getMessage());
            }
            // §3.3：脚本侧最小调用间隔保护（QPS 令牌桶之外的兜底）
            if (comboIntervalMs > 0 && i < combos.size() - 1) {
                LockSupport.parkNanos(comboIntervalMs * 1_000_000L);
            }
        }

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        return new CollectResult(new ArrayList<>(merged.values()), combos.size(), failedQueries, elapsedMs);
    }

    /** 去重合并后的采集条目：POI + 命中关键词集合（按命中先后排序） */
    public static final class CollectedPoi {
        private RawPoi poi;
        private final Set<String> hitKeywords = new LinkedHashSet<>();

        private CollectedPoi(RawPoi poi) {
            this.poi = poi;
        }

        public RawPoi getPoi() {
            return poi;
        }

        /** 命中关键词/编码，分号拼接（§3.4 collect_keywords 列） */
        public String hitKeywordsJoined() {
            return String.join(";", hitKeywords);
        }
    }

    /**
     * 采集结果与汇总（§3.3 结束时输出汇总：成功 X 条 / 失败 Y 个查询 / 耗时 Z s）。
     */
    public record CollectResult(List<CollectedPoi> pois, int totalQueries, int failedQueries,
                                long elapsedMs) {

        public int poiCount() {
            return pois.size();
        }
    }
}
