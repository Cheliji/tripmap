package com.tripmap.dal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 图执行 checkpoint 表实体（表 session_checkpoint，见《tripmap数据库设计说明书》§3.3）。
 * 追加式快照：seq 会话内递增，恢复查询固定取 session_id 最新一行。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("session_checkpoint")
public class SessionCheckpointEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    /** 会话内递增序号 */
    private Integer seq;

    /** 最后完成的节点名 */
    private String node;

    /** 接下来执行的节点列表 JSON */
    private String nextNodes;

    /**
     * 完整图状态序列化 JSON。
     * 持久通道（背景/候选/聚类/usage）入列；瞬态通道（通勤矩阵/天气）不入列，恢复时空白即重跑。
     */
    private String stateBlob;

    private LocalDateTime createdAt;
}
