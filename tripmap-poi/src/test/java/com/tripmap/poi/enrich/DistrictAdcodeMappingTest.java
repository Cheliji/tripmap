package com.tripmap.poi.enrich;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * district_adcode 静态映射单测（tripmap-poi功能设计说明书 §4.3）
 *
 * <p>杭州首版映射表（项目方 2026-11-05 提供）：含撤并旧码兼容（下城区/江干区/富阳/临安），
 * "拱野区"按"拱墅区"录入。未命中/空白返回空，由调用方记 warn（不标 FAILED）。</p>
 */
class DistrictAdcodeMappingTest {

    private final DistrictAdcodeMapping mapping = DistrictAdcodeMapping.loadDefault();

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "西湖区, 330106", "拱墅区, 330105", "上城区, 330102",
            "下城区, 330103", "江干区, 330104", "滨江区, 330108",
            "萧山区, 330109", "余杭区, 330110", "桐庐县, 330122",
            "淳安县, 330127", "建德市, 330182", "富阳市, 330183",
            "临安市, 330185", "市辖区, 330101"
    })
    void 杭州首版映射表逐行命中(String adname, String expectedAdcode) {
        assertEquals(expectedAdcode, mapping.lookup(adname).orElseThrow());
    }

    @Test
    void 未命中与空白返回空() {
        assertTrue(mapping.lookup("不存在的区").isEmpty());
        assertTrue(mapping.lookup(null).isEmpty());
        assertTrue(mapping.lookup("  ").isEmpty());
    }
}
