package com.tripmap.tool.cluster.dto;

import com.tripmap.tool.poi.PoiCandidate;
import com.tripmap.tool.spi.ToolInput;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 空间聚类工具输入（任务 2.5，设计说明书 §9：输入 POI 列表、k）。
 *
 * <p>候选直接复用 2.2 召回输出载体 {@link PoiCandidate}（已带 GCJ-02 坐标与
 * mustVisit 标记），编排节点装配时透传即可，不重造 POI 结构。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClusterInput implements ToolInput {

    /** 候选 POI（3.5 LLM 精选后的 top-N，N≈天数×5；必去点 mustVisit=true） */
    private List<PoiCandidate> candidates;

    /** 行程天数（k = 簇数，"一天一个区域"，设计说明书 §6.2） */
    private Integer days;

    /**
     * 随机种子（K-Means 初始质心抽样用，保证同输入同输出）；
     * null 时使用配置默认值 {@code tripmap.cluster.seed}
     */
    private Long seed;
}
