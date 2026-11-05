package com.tripmap.poi.collect;

/**
 * 采集策略枚举（任务 1.3.1，设计说明书 §3.2）
 *
 * <p>{@code EXCLUDE} 的类别（如 110106 公园内部设施）仅作白名单文档留痕，
 * 矩阵展开为查询组合时会被过滤，不参与采集。</p>
 */
public enum CategoryStrategy {

    /** 采集 */
    COLLECT,

    /** 排除（仅文档留痕，不展开为查询） */
    EXCLUDE
}
