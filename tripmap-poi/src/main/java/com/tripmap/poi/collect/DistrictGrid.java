package com.tripmap.poi.collect;

import java.util.List;

/**
 * 城市分区格（任务 1.3.1，设计说明书 §3.2）
 *
 * <p>按高德 adcode 分区逐格采集，保证每格（分区 × 分类）候选量不超过翻页深度（§3.3）。
 * {@code clusters} 标注该分区覆盖的设计簇（西湖环线、灵隐—龙井、西溪、良渚、钱江新城等），
 * 仅供矩阵可读性与后续分析，不参与请求参数。</p>
 *
 * @param adcode   区县级 adcode（6 位数字，如 330106 西湖区）
 * @param name     区县名（文档可读性）
 * @param clusters 覆盖的设计簇列表
 */
public record DistrictGrid(String adcode, String name, List<String> clusters) {

    public DistrictGrid {
        if (adcode == null || !adcode.matches("\\d{6}")) {
            throw new IllegalArgumentException("分区 adcode 必须为 6 位数字: " + adcode);
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("分区名称不能为空");
        }
        clusters = clusters == null ? List.of() : List.copyOf(clusters);
    }
}
