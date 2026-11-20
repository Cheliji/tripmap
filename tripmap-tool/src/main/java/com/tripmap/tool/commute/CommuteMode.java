package com.tripmap.tool.commute;

/**
 * 通勤出行方式（任务 2.4.1，amap-route-api.md §0 快速索引）。
 *
 * <p>MVP 市内通勤只暴露公交/驾车两种；v5 步行/骑行接口（amap-route-api.md 未展开）
 * 预留枚举扩展位，不在本任务实现。</p>
 */
public enum CommuteMode {

    /** 公交/地铁（v5 公交路径规划，同城传 citycode） */
    BUS("公交"),

    /** 驾车（v5 驾车路径规划） */
    DRIVING("驾车");

    private final String label;

    CommuteMode(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
