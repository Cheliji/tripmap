package com.tripmap.common.constant;

import java.util.List;
import java.util.Set;

/**
 * POI 标签受控词表（tripmap-poi功能设计说明书 §4.5，闭集 20 个）
 *
 * <p>唯一事实源：功能二 Prompt、功能三导入校验、运行时召回打分（TASKS 2.2.2）
 * 三处共用本定义，禁止各自维护副本（防规则漂移）。词表调整只改本类。</p>
 *
 * <p>维度（人群/体验）仅存在于词表文档与打分权重配置中，存储与搜索不感知维度，
 * 库内扁平混存、Excel 中以分号分隔写入。</p>
 */
public final class PoiTagVocab {

    // ---- 人群适配（5）----
    public static final String FAMILY = "亲子";
    public static final String SENIOR_FRIENDLY = "老人友好";
    public static final String STROLLER_FRIENDLY = "婴儿车友好";
    public static final String ACCESSIBLE = "无障碍";
    public static final String HIGH_STAMINA = "高体力";

    // ---- 体验属性（15）----
    public static final String INDOOR = "室内";
    public static final String NIGHT_VIEW = "夜景";
    public static final String NICHE = "小众";
    public static final String PHOTO_SPOT = "拍照打卡";
    public static final String CULTURE_DEEP = "文化深度";
    public static final String LAKE_VIEW = "湖景";
    public static final String ANCIENT_ARCH = "古建筑";
    public static final String GARDEN = "园林景观";
    public static final String SHOW = "演出演艺";
    public static final String FOOD_HUB = "美食聚集";
    public static final String HIKING = "徒步登山";
    public static final String WATERSIDE = "亲水";
    public static final String EXHIBITION = "展馆陈列";
    public static final String LANDMARK = "城市地标";
    public static final String SHOPPING_DINING = "购物餐饮";

    /** 全量闭集（校验唯一依据：词表外标签即非法，§4.5） */
    public static final Set<String> ALL = Set.of(
            FAMILY, SENIOR_FRIENDLY, STROLLER_FRIENDLY, ACCESSIBLE, HIGH_STAMINA,
            INDOOR, NIGHT_VIEW, NICHE, PHOTO_SPOT, CULTURE_DEEP, LAKE_VIEW, ANCIENT_ARCH,
            GARDEN, SHOW, FOOD_HUB, HIKING, WATERSIDE, EXHIBITION, LANDMARK, SHOPPING_DINING);

    private PoiTagVocab() {
    }

    /** 标签是否在受控词表内 */
    public static boolean isValid(String tag) {
        return tag != null && ALL.contains(tag);
    }

    /** Prompt 用分组展示文本（判断标准清晰、输出稳定，§4.5） */
    public static String groupedPromptText() {
        return "人群适配（多选）：" + String.join("、", List.of(
                FAMILY, SENIOR_FRIENDLY, STROLLER_FRIENDLY, ACCESSIBLE, HIGH_STAMINA))
                + "\n体验属性（多选）：" + String.join("、", List.of(
                INDOOR, NIGHT_VIEW, NICHE, PHOTO_SPOT, CULTURE_DEEP, LAKE_VIEW, ANCIENT_ARCH,
                GARDEN, SHOW, FOOD_HUB, HIKING, WATERSIDE, EXHIBITION, LANDMARK, SHOPPING_DINING));
    }
}
