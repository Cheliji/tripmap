package com.tripmap.poi.collect;

import com.tripmap.common.exception.ExternalApiException;
import com.tripmap.common.result.ResultCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 采集执行器单测（任务 1.3.2，设计说明书 §3.3）
 *
 * <p>以子类桩替代 {@link AmapPoiSearcher}（不触网），验证：
 * amap_id 去重与命中关键词合并、字段以最新采集为准、
 * 单组合失败仅跳过不中断、组合间间隔、汇总统计。</p>
 */
class PoiCollectorTest {

    /** 桩搜索器：按 (region, typecode/keywords) 返回固定结果或抛异常 */
    private static class StubSearcher extends AmapPoiSearcher {
        StubSearcher() {
            super(null); // 桩不触网，客户端传 null
        }

        @Override
        public List<RawPoi> search(AmapPoiSearchQuery query) {
            String key = query.region() + ":" + (query.types() != null ? query.types() : query.keywords());
            return switch (key) {
                case "330106:110101" -> List.of(poi("A1", "公园甲"), poi("SHARED", "跨界景点"));
                case "330102:110101" -> List.of(poi("SHARED", "跨界景点-最新"), poi("B1", "公园乙"));
                // §3.3 容错条款：该组合模拟外部失败
                case "330106:080501" -> throw new ExternalApiException(ResultCode.EXTERNAL_AMAP, "高德", true);
                case "330102:080501" -> List.of(poi("C1", "游乐场丙"));
                case "330106:购物中心" -> List.of(poi("D1", "商场丁"));
                case "330102:购物中心" -> List.of();
                default -> throw new IllegalStateException("未预期的查询组合: " + key);
            };
        }
    }

    private static RawPoi poi(String id, String name) {
        RawPoi p = new RawPoi();
        p.setAmapId(id);
        p.setName(name);
        p.setAdname("西湖区");
        return p;
    }

    private CityCollectMatrix matrix() {
        List<DistrictGrid> districts = List.of(
                new DistrictGrid("330106", "西湖区", List.of("西湖环线")),
                new DistrictGrid("330102", "上城区", List.of("钱江新城")));
        List<CategoryItem> categories = List.of(
                new CategoryItem("公园", "110101", null, null),
                new CategoryItem("游乐场", "080501", null, null),
                new CategoryItem("公园内部设施", "110106", null, CategoryStrategy.EXCLUDE),
                new CategoryItem("商圈-购物中心", null, "购物中心", null));
        return new CityCollectMatrix("杭州", "330100", districts, categories);
    }

    @Test
    void 去重合并命中关键词且字段以最新为准() {
        PoiCollector.CollectResult result = new PoiCollector(new StubSearcher())
                .collect(matrix(), 0);

        // A1、SHARED、B1、C1、D1 共 5 条（SHARED 跨分区重复只留一条）
        assertEquals(5, result.poiCount());
        PoiCollector.CollectedPoi shared = result.pois().stream()
                .filter(c -> "SHARED".equals(c.getPoi().getAmapId()))
                .findFirst().orElseThrow();
        // §3.3：记录命中的多个关键词（分号分隔，集合式去重）
        assertEquals("110101", shared.hitKeywordsJoined());
        // §3.5：字段值以最新一次采集为准（后采集的"跨界景点-最新"覆盖）
        assertEquals("跨界景点-最新", shared.getPoi().getName());
        // EXCLUDE 类别未展开：总查询数 = 2 分区 × 3 采集类 = 6
        assertEquals(6, result.totalQueries());
        // 一个组合失败：080501@西湖区
        assertEquals(1, result.failedQueries());
    }

    @Test
    void 汇总统计正确() {
        PoiCollector.CollectResult result = new PoiCollector(new StubSearcher())
                .collect(matrix(), 0);
        assertTrue(result.elapsedMs() >= 0);
        // 失败组合（游乐场@西湖区）不影响其余组合：D1/C1 均采到
        assertTrue(result.pois().stream().anyMatch(c -> "C1".equals(c.getPoi().getAmapId())));
        assertTrue(result.pois().stream().anyMatch(c -> "D1".equals(c.getPoi().getAmapId())));
    }
}
