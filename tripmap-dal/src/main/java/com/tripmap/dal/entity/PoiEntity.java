package com.tripmap.dal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * POI 景点表实体（表 poi，见 sql/001_poi.sql 与《tripmap数据库设计说明书》§3.1）。
 * JSON 列（tags/open_hours）映射为 String，内容三态语义校验收敛在代码层。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("poi")
public class PoiEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 高德POI ID，业务唯一键 */
    private String amapId;

    private String name;

    /** 高德原始分类（分号多段） */
    private String type;

    /** 高德分类编码（可能多值，竖线分隔） */
    private String typecode;

    /** 特征标签（如5A景区） */
    private String keytag;

    /** 景区等级（AAAAA等） */
    private String level;

    private String city;

    /** 行政区名（高德adname） */
    private String district;

    /** 区县行政区划码 GB/T 2260 */
    private Integer districtAdcode;

    /** 经度 GCJ-02 */
    private BigDecimal longitude;

    /** 纬度 GCJ-02 */
    private BigDecimal latitude;

    /** 项目内分类（8枚举中文标签，PoiCategory） */
    private String category;

    /** 标签数组 JSON（受控词表闭集20个，不参与SQL过滤） */
    private String tags;

    /** 高德评分 */
    private BigDecimal rating;

    /** 人均消费（元） */
    private BigDecimal cost;

    /** 门票价格，0=免费 */
    private BigDecimal ticketPrice;

    /** 建议游玩时长（分钟），检索硬过滤字段 */
    private Integer suggestDuration;

    /** 结构化开放时段 JSON：mon~sun七键，[]=闭馆，null=未知 */
    private String openHours;

    /** 高德原始营业说明全文（open_hours 展示兜底） */
    private String opentime2;

    /** 简介 */
    private String brief;

    private String address;

    private String tel;

    /** 图片URL（分号分隔） */
    private String photos;

    /** 景点公众号名称 */
    private String officialAccount;

    /** AI生成字段声明 */
    private String remark;

    /** 1正常 2闭园 3下架（检索硬过滤字段） */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
