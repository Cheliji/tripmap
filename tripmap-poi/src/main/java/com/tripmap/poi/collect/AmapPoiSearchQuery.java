package com.tripmap.poi.collect;

import org.springframework.util.StringUtils;

/**
 * 高德 place/text 搜索查询条件（任务 1.1.3）
 *
 * <p>一次查询对应（分区 × 分类）矩阵中的一个组合（设计说明书 §3.2/§3.3）。
 * 类型检索一律用 {@code types} 分类编码（多值竖线分隔），不用裸关键词（§3.2 实测噪声大）；
 * {@code keywords} 仅作补充手段保留。</p>
 *
 * @param keywords 关键词，可空（与 types 至少其一非空）
 * @param types    高德 POI 分类编码，多值用竖线分隔（如 "110101|110102"），可空
 * @param city     城市名（配合 citylimit 限定仅本市数据）
 * @param cityLimit 是否仅返回本市数据（adcode 与 city 不匹配的数据会被过滤）
 * @param region   分区 adcode（如 "330106" 西湖区），可空；用于把大类候选量切到翻页深度内（§3.3）
 * @param pageSize 每页条数，默认 25（高德 place/text 上限，§3.3 实测）
 * @param maxPages 单查询最大翻页深度，默认 9（实测第 10 页起返回空列表且 status=1 不报错，§3.3）
 */
public record AmapPoiSearchQuery(
        String keywords,
        String types,
        String city,
        boolean cityLimit,
        String region,
        int pageSize,
        int maxPages) {

    /** 高德 place/text 每页条数上限（§3.3 实测） */
    public static final int MAX_PAGE_SIZE = 25;

    /** 单查询最大翻页深度：实测第 10 页起返回空列表且 status=1 不报错，官方 count 字段不可作为翻页依据（§3.3） */
    public static final int DEFAULT_MAX_PAGES = 9;

    public AmapPoiSearchQuery {
        if (!StringUtils.hasText(keywords) && !StringUtils.hasText(types)) {
            throw new IllegalArgumentException("keywords 与 types 不能同时为空：类型检索应传 types 分类编码（设计说明书 §3.2）");
        }
        if (!StringUtils.hasText(city)) {
            throw new IllegalArgumentException("city 不能为空：单城市采集约定（设计说明书 §3.1）");
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("pageSize 必须在 1~" + MAX_PAGE_SIZE + " 之间（高德上限 " + MAX_PAGE_SIZE + "）");
        }
        if (maxPages < 1) {
            throw new IllegalArgumentException("maxPages 必须 >= 1");
        }
        // 空白串归一为 null，避免拼进请求参数产生噪声
        keywords = StringUtils.hasText(keywords) ? keywords.trim() : null;
        types = StringUtils.hasText(types) ? types.trim() : null;
        city = city.trim();
        region = StringUtils.hasText(region) ? region.trim() : null;
    }

    /**
     * 按类型编码检索的标准查询（矩阵主用法，§3.2）。
     */
    public static AmapPoiSearchQuery byTypes(String types, String city, String region) {
        return new AmapPoiSearchQuery(null, types, city, true, region,
                MAX_PAGE_SIZE, DEFAULT_MAX_PAGES);
    }
}
