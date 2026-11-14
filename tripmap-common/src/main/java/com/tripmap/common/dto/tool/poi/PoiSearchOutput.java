package com.tripmap.common.dto.tool.poi;

import com.tripmap.common.dto.tool.ToolOutput;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * POI 检索工具输出（任务 2.2）。
 *
 * <p>{@code candidates} 为"硬过滤 + 语义召回"后的候选清单（20～40 个），
 * 供候选筛选节点做 LLM 受约束精选；必去景点经预检后应保证出现在清单中。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "POI 检索工具输出")
public class PoiSearchOutput implements ToolOutput {

    @Schema(description = "候选 POI 列表（按召回打分降序）")
    private List<PoiCandidate> candidates;

    @Schema(description = "命中总数（供节点日志与调试）")
    private Integer totalHit;
}
