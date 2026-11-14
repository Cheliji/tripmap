package com.tripmap.dal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 行程天表实体（表 itinerary_day，见《tripmap数据库设计说明书》§3.6）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("itinerary_day")
public class ItineraryDayEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long itineraryId;

    /** 第几天 */
    private Integer daySeq;

    private LocalDate date;

    /** 当日区域一句话（天卡片标题） */
    private String areaSummary;

    /** 当日天气快照 JSON（规划时点预报，非实时查询） */
    private String weatherSnapshot;

    /** 终点商圈/枢纽提示（"建议住宿区域"） */
    private String hotelHint;

    /** 当日替补景点id清单 JSON */
    private String substitutePoiIds;

    /** 当日通勤合计（分钟） */
    private Integer totalCommuteMinutes;

    private LocalDateTime createdAt;
}
