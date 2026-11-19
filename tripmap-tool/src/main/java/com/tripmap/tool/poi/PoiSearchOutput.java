package com.tripmap.tool.poi;

import com.tripmap.tool.spi.ToolOutput;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * POI 检索工具输出 DTO（任务 2.2）。
 *
 * <p>candidates 按打分降序排列，必去钉住项恒在头部；
 * 平分时 rating 降序 → id 升序，保证确定性（TASKS 2.2.2）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PoiSearchOutput implements ToolOutput {

    /** 截断后的候选列表（必去钉住项 + 打分 top-N，N = 收敛后的 limit） */
    private List<PoiCandidate> candidates;

    /** 硬过滤后的候选池大小（截断前） */
    private int poolSize;

    /** 候选池不足配置下限（默认 20）：上游应感知候选稀缺（可能触发放宽条件或 300002） */
    private boolean poolExhausted;

    /** 必去景点中与硬过滤冲突（闭园/下架/超预算/超时长/库中不存在）的 ID 列表 */
    private List<Long> mustVisitConflictIds;
}
