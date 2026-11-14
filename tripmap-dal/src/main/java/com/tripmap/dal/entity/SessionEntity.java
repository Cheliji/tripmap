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
 * 规划会话表实体（表 session，见《tripmap数据库设计说明书》§3.2）。
 * stage 取值：COLLECTING / PLANNING / DONE / FAILED。
 * itineraryId 为逻辑指针（与 itinerary.sessionId 互指，无物理外键）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("session")
public class SessionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户ID（MVP无登录体系，预留；空=匿名） */
    private Long userId;

    /** 目的地（意图拒绝R1也记录，用于统计被拒流量） */
    private String city;

    /** 背景Schema当前定稿 JSON（§12 五要素） */
    private String backgroundSchema;

    /** 会话阶段：COLLECTING/PLANNING/DONE/FAILED */
    private String stage;

    /** 行程制作次数（含评测回退重跑） */
    private Integer planCount;

    /** 当前有效行程id（逻辑指针，不设外键） */
    private Long itineraryId;

    /** 首个请求X-Trace-ID */
    private String clientTraceId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
