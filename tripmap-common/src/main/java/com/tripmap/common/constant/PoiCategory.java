package com.tripmap.common.constant;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * POI 项目内分类枚举（tripmap-poi功能设计说明书 §4.4，8 枚举）
 *
 * <p>category 由 typecode 规则映射产出（纯代码、不经 LLM，零幻觉面）；
 * Excel 中存中文标签（{@link #getLabel()}），功能三导入校验与运行时消费共用本枚举。</p>
 */
public enum PoiCategory {

    /** 文博展馆（科教文化服务系，140 前缀兜底） */
    CULTURE("文博展馆"),
    /** 亲子游乐（080501 游乐场） */
    PLAY("亲子游乐"),
    /** 宗教场所（寺庙道观/教堂/回教寺） */
    RELIGION("宗教场所"),
    /** 人文古迹（纪念馆/红色景区） */
    HERITAGE("人文古迹"),
    /** 名胜自然（世界遗产/国家级/省级景点/海滩/观景点/风景名胜） */
    SCENERY("名胜自然"),
    /** 公园乐园（公园/动物园/植物园/水族馆/公园广场） */
    PARK("公园乐园"),
    /** 城市广场 */
    SQUARE("城市广场"),
    /** 商圈街区（购物服务系，06 前缀） */
    BUSINESS("商圈街区");

    private final String label;

    PoiCategory(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 全部合法中文标签（功能三导入校验用，§5） */
    public static Set<String> labels() {
        return Arrays.stream(values()).map(PoiCategory::getLabel).collect(Collectors.toSet());
    }
}
