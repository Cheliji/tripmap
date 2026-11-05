package com.tripmap.poi.collect;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 采集矩阵加载器单测（任务 1.3.1）
 *
 * <p>验证默认资源杭州矩阵的完整性：分区覆盖设计簇、类型白名单含 §3.2 全部编码、
 * EXCLUDE 留痕项被过滤、组合展开规模可控、未定义城市报错。</p>
 */
class CollectMatrixLoaderTest {

    private final CollectMatrixLoader loader = new CollectMatrixLoader();

    @Test
    void 默认资源包含杭州完整矩阵() {
        CityCollectMatrix matrix = loader.loadDefault("杭州");

        assertEquals("杭州", matrix.city());
        assertEquals("330100", matrix.cityAdcode());
        // 分区：主城六区 + 余杭/萧山/富阳/临安/临平/钱塘 + 淳安/桐庐/建德
        assertEquals(13, matrix.districts().size());
        // 类型白名单：26 个采集 + 1 个 EXCLUDE 留痕（110106 公园内部设施）
        assertEquals(27, matrix.categories().size());
        assertTrue(matrix.categories().stream()
                .anyMatch(c -> "110106".equals(c.typecode()) && !c.collect()));
        // §3.2 补充类型齐全
        for (String required : new String[]{"080501", "140100", "140400", "110200", "110205"}) {
            assertTrue(matrix.categories().stream().anyMatch(c -> required.equals(c.typecode())),
                    "白名单缺少类型 " + required);
        }
        // 商圈关键词辅助采集（§3.2）
        assertTrue(matrix.categories().stream()
                .anyMatch(c -> "购物中心".equals(c.keywords())));
    }

    @Test
    void 组合展开过滤EXCLUDE且规模可控() {
        CityCollectMatrix matrix = loader.loadDefault("杭州");
        var combos = matrix.combos();

        // 13 分区 × (27 - 1 EXCLUDE) = 338 个组合；3 QPS + 100ms 间隔约 2.5 分钟内完成（§3.5 分钟级）
        assertEquals(338, combos.size());
        assertFalse(combos.stream().anyMatch(c -> "110106".equals(c.category().typecode())),
                "EXCLUDE 类别不得展开为查询");
        // 组合 → 查询：分区 adcode 进入 region，城市进入 city
        CityCollectMatrix.Combo combo = combos.get(0);
        AmapPoiSearchQuery query = combo.toQuery();
        assertEquals("杭州", query.city());
        assertEquals(combo.district().adcode(), query.region());
        assertTrue(query.cityLimit());
        assertNotNull(query.types());
    }

    @Test
    void 未定义城市报错并给出补录指引() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> loader.loadDefault("西安"));
        assertTrue(e.getMessage().contains("西安"));
        assertTrue(e.getMessage().contains("collect-matrix.json"), "报错应指引补录资源文件");
    }

    @Test
    void 资源不存在报错() {
        assertThrows(IllegalStateException.class,
                () -> loader.load("keywords/not-exist.json", "杭州"));
    }

    @Test
    void 非法矩阵在加载时被拒绝() {
        // 分区 adcode 非 6 位数字：DistrictGrid 紧凑构造器校验
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> loader.load("keywords/collect-matrix-invalid.json", "测试城"));
        assertTrue(e.getMessage().contains("采集矩阵解析失败"));
    }
}
