package com.tripmap.tool.cluster.dto;

import com.tripmap.tool.poi.PoiCandidate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 单日簇（任务 2.5 输出项）：一天对应的景点区域。
 *
 * <p>簇到天的排序已在工具内完成（按簇质心贪心最近邻，降低换区成本，
 * 设计说明书 §6.3"日序安排"），{@link #daySeq} 从 1 开始连续编号，
 * 消费方（3.6 通勤矩阵按簇两两调用）直接按序取。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DayCluster {

    /** 日序号（1..k，已按簇质心邻近关系排序） */
    private int daySeq;

    /** 簇内景点（保持输入候选顺序，日内顺序由 2.6 TSP 决定，本工具不排序） */
    private List<PoiCandidate> points;

    /** 簇质心纬度（锚定簇 = 必去组均值坐标，自由簇 = 成员均值坐标） */
    private double centroidLat;

    /** 簇质心经度 */
    private double centroidLng;

    /** 是否锚定簇（含必去景点组，质心在迭代中固定不动） */
    private boolean anchored;
}
