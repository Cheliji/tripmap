package com.tripmap.poi.collect;

import org.springframework.util.StringUtils;

/**
 * 采集分类项（任务 1.3.1，设计说明书 §3.2）
 *
 * <p>主用 {@code typecode} 分类编码检索（噪声小）；{@code keywords} 仅作初期辅助手段
 * （如商圈：实测分类编码口径不稳，按 §3.2 用"购物中心/步行街"关键词辅助采集）。</p>
 *
 * @param label    类别中文名（文档可读性，也用于日志）
 * @param typecode 高德 POI 分类编码（与 keywords 二选一）
 * @param keywords 检索关键词（与 typecode 二选一）
 * @param strategy 采集策略，缺省 COLLECT；EXCLUDE 仅文档留痕不参与采集
 */
public record CategoryItem(String label, String typecode, String keywords, CategoryStrategy strategy) {

    public CategoryItem {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("分类名称 label 不能为空");
        }
        boolean hasTypecode = StringUtils.hasText(typecode);
        boolean hasKeywords = StringUtils.hasText(keywords);
        if (hasTypecode == hasKeywords) {
            throw new IllegalArgumentException(
                    "分类 " + label + " 必须且只能配置 typecode 或 keywords 之一（设计说明书 §3.2）");
        }
        // Jackson 反序列化缺省值为 null，归一为 COLLECT
        strategy = strategy == null ? CategoryStrategy.COLLECT : strategy;
        if (hasTypecode) {
            typecode = typecode.trim();
            keywords = null;
        } else {
            keywords = keywords.trim();
            typecode = null;
        }
    }

    public boolean collect() {
        return strategy == CategoryStrategy.COLLECT;
    }
}
