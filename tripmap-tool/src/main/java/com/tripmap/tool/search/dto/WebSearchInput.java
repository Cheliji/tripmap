package com.tripmap.tool.search.dto;

import com.tripmap.tool.spi.ToolInput;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 联网搜索工具输入 DTO（任务 2.7，设计说明书 §9 工具层：输入=查询词）。
 *
 * <p>由编排节点从图 State 与已产出结果确定性装配（如城市概览、逐日攻略、
 * 注意事项各自的查询词模板），非模型临场生成。查询词支持 DeepSeek 官方
 * 搜索语法内嵌（site:/after:/lang: 等操作符原样透传，见
 * {@code DeepSeekSearchClient}）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebSearchInput implements ToolInput {

    /**
     * 查询词（必填，1～128 字符，DeepSeek 官方上限；超长由工具层拒绝）。
     * 时间过滤用 {@code after:yyyy-MM-dd} 操作符内嵌查询词，无独立参数。
     */
    private String query;
}
