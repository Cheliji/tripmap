package com.tripmap.poi.enrich;

import com.tripmap.common.constant.PoiCategory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * category 规则映射单测（tripmap-poi功能设计说明书 §4.4）
 *
 * <p>表即规则：每条映射一行参数化测试（§4.4 维护约定），
 * 另有多值裁决/排除/未命中三组边界测试。映射总表见
 * resources/mapping/category-mapping.json（自上而下即优先级）。</p>
 */
class CategoryMappingTest {

    private final CategoryMapping mapping = CategoryMapping.loadDefault();

    /** 映射总表逐行参数化（每个前缀码 → 期望分类标签） */
    @ParameterizedTest(name = "typecode={0} → {1}")
    @CsvSource({
            // 优先级 1：文博展馆（科教文化服务系）
            "140100, 文博展馆", "140200, 文博展馆", "140400, 文博展馆",
            "140500, 文博展馆", "140600, 文博展馆", "140999, 文博展馆",
            // 优先级 2：亲子游乐
            "080501, 亲子游乐",
            // 优先级 3：宗教场所
            "110205, 宗教场所", "110206, 宗教场所", "110207, 宗教场所",
            // 优先级 4：人文古迹
            "110204, 人文古迹", "110210, 人文古迹",
            // 优先级 5：名胜自然
            "110201, 名胜自然", "110202, 名胜自然", "110203, 名胜自然",
            "110208, 名胜自然", "110209, 名胜自然", "110200, 名胜自然", "110000, 名胜自然",
            // 优先级 6：公园乐园
            "110101, 公园乐园", "110102, 公园乐园", "110103, 公园乐园",
            "110104, 公园乐园", "110100, 公园乐园",
            // 优先级 7：城市广场
            "110105, 城市广场",
            // 优先级 8：商圈街区（06 前缀）
            "060100, 商圈街区", "060200, 商圈街区", "061202, 商圈街区", "069999, 商圈街区"
    })
    void 映射总表逐行命中(String typecode, String expectedLabel) {
        Optional<PoiCategory> category = mapping.map(typecode);
        assertTrue(category.isPresent(), "typecode=" + typecode + " 应命中映射");
        assertEquals(expectedLabel, category.get().getLabel());
    }

    @Test
    void 多值裁决140优先于06() {
        assertEquals("文博展馆", mapping.map("061202|140200|170200").orElseThrow().getLabel());
    }

    @Test
    void 多值裁决游乐场不被商圈抢占() {
        assertEquals("亲子游乐", mapping.map("080501|060000").orElseThrow().getLabel());
    }

    @Test
    void 多值裁决商圈兜底命中() {
        assertEquals("商圈街区", mapping.map("170200|060000").orElseThrow().getLabel());
    }

    @Test
    void 排除项110106视为未命中() {
        assertTrue(mapping.map("110106").isEmpty(), "公园内部设施（售票处/出入口）不映射（§3.2 排除）");
    }

    @Test
    void 未命中返回空不标失败() {
        assertTrue(mapping.map("990000").isEmpty());
        assertTrue(mapping.map("").isEmpty());
        assertTrue(mapping.map(null).isEmpty());
        assertTrue(mapping.map("   ").isEmpty());
        assertTrue(mapping.map("|").isEmpty());
    }
}
