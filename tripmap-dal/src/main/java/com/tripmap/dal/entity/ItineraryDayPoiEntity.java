package com.tripmap.dal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 行程日内站点表实体（表 itinerary_day_poi，见《tripmap数据库设计说明书》§3.7）。
 * (dayId, seq) 唯一：攻略节点只准按 seq 顺序叙述（§1 原则 4），改序在数据层不可能静默发生。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("itinerary_day_poi")
public class ItineraryDayPoiEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long dayId;

    /** 访问顺序 */
    private Integer seq;

    /** POIid（逻辑外键关联 poi，应用层校验） */
    private Long poiId;

    /** 名称冗余快照：POI改名/下线后历史行程仍可读 */
    private String poiNameSnapshot;

    private LocalTime arriveTime;

    private LocalTime departTime;

    /** 含1.2倍缓冲的游玩占用（分钟） */
    private Integer playDurationMinutes;

    /** 与上一站通勤分钟数，首站为0 */
    private Integer commutePrevMinutes;

    /** 提前到达等开门耗时（分钟，等待计入总耗时） */
    private Integer waitMinutes;

    private LocalDateTime createdAt;
}
