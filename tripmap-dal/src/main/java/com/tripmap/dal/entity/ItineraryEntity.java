package com.tripmap.dal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 行程表实体（表 itinerary，单版本设计，见《tripmap数据库设计说明书》§3.5）。
 * sessionId 业务唯一；重跑 = 应用层删旧插新（连带 day/day_poi），plan_count 记 session。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("itinerary")
public class ItineraryEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会话id，业务唯一（单版本设计） */
    private Long sessionId;

    /** 摘要标题（结果页顶部） */
    private String title;

    /** 行程天数 */
    private Integer dayCount;

    private LocalDate startDate;

    private LocalDate endDate;

    /** 门票+餐饮+市内交通估算合计（硬校验#1结论） */
    private BigDecimal totalCostEst;

    /** 各LLM节点token消耗明细 JSON（成本归因） */
    private String totalTokens;

    /** 最终版评测证据包 JSON（五项硬校验明细+LLM软评分） */
    private String evalResult;

    private LocalDateTime createdAt;
}
