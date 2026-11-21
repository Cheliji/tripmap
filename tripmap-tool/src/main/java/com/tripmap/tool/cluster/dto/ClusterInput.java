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
     * 禁配点对表（可选，任务 2.5.9 通勤时间禁配回切环）：列出的两个 POI 不得排在
     * 同一天，由编排层（3.6）在通勤矩阵/硬校验 #4 发现"同天但通勤超时"时注入，
     * 回灌模型重解。null/空表示无禁配。
     */
    private List<ForbiddenPair> forbiddenPairs;
}
