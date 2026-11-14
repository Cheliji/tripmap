package com.tripmap.common.dto.tool.route;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 开放时间时段（任务 2.6.2 时间窗剪枝的载体，设计说明书 §6.3）。
 *
 * <p>由规划计算链节点按出行日期推算星期后，从 POI 的 open_hours
 * （§8.1 三态语义）解析出"当日"的确定开放时段；时段外访问需等待计入耗时。</p>
 *
 * @param openMinute  开放时刻（分钟，含边界；0=00:00）
 * @param closeMinute 关闭时刻（分钟；1440=24:00 当日结束）
 */
@Schema(description = "开放时间时段")
public record TimeWindow(@Schema(description = "开放时刻（分钟）") int openMinute,
                         @Schema(description = "关闭时刻（分钟，1440=24:00）") int closeMinute) {

    /**
     * 紧凑构造器：时段必须 0 ≤ open &lt; close ≤ 1440（§8.1 校验口径，close 允许 24:00）。
     */
    public TimeWindow {
        if (openMinute < 0 || closeMinute > 24 * 60 || openMinute >= closeMinute) {
            throw new IllegalArgumentException(
                    "非法开放时段: " + openMinute + "-" + closeMinute + "（须 0 ≤ open < close ≤ 1440）");
        }
    }
}
