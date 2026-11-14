package com.tripmap.common.dto.tool.search;

import com.tripmap.common.dto.tool.ToolOutput;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 联网搜索工具输出（任务 2.7）：全部条目强制带 {@code url} 来源。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "联网搜索工具输出")
public class WebSearchOutput implements ToolOutput {

    @Schema(description = "查询词（回显，便于节点日志与溯源）")
    private String query;

    @Schema(description = "检索结果条目（全部带来源 URL）")
    private List<SearchHit> hits;
}
