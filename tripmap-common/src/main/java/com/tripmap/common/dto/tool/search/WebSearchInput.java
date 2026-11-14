package com.tripmap.common.dto.tool.search;

import com.tripmap.common.dto.tool.ToolInput;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 联网搜索工具输入（任务 2.7，设计说明书 §9）。
 *
 * <p>供攻略生成节点（城市概览/注意事项素材）与对话环节必去景点搜索使用；
 * 供应商已定 DeepSeek 官方联网搜索（不使用博查）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "联网搜索工具输入")
public class WebSearchInput implements ToolInput {

    @Schema(description = "查询词")
    private String query;

    @Schema(description = "期望结果条数", example = "10")
    private Integer topK;
}
