package com.tripmap.poi.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import com.tripmap.poi.collect.RawPoi;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * raw Excel 行模型（任务 1.3.2，列定义见设计说明书 §3.4，2026-11-05 按真实响应修订）
 *
 * <p>= {@link RawPoi} 全部高德字段 + 采集侧两列（collect_keywords / collected_at）。
 * 列顺序与 §3.4 表严格一致，作为功能二增强 Excel 的原样保留底列。
 * Lombok 注解消除样板代码（AGENTS.md 第 6 节）；EasyExcel 反射实例化依赖无参构造。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RawPoiExcelRow {

    @ExcelProperty("amap_id")
    private String amapId;
    @ExcelProperty("name")
    private String name;
    @ExcelProperty("type")
    private String type;
    @ExcelProperty("typecode")
    private String typecode;
    @ExcelProperty("keytag")
    private String keytag;
    @ExcelProperty("level")
    private String level;
    @ExcelProperty("rating")
    private String rating;
    @ExcelProperty("open_time")
    private String openTime;
    @ExcelProperty("opentime2")
    private String opentime2;
    @ExcelProperty("cost")
    private String cost;
    @ExcelProperty("tel")
    private String tel;
    @ExcelProperty("address")
    private String address;
    /** GCJ-02 坐标，存字符串不转数值（§3.4） */
    @ExcelProperty("location")
    private String location;
    @ExcelProperty("pname")
    private String pname;
    @ExcelProperty("cityname")
    private String cityname;
    @ExcelProperty("adname")
    private String adname;
    @ExcelProperty("photos")
    private String photos;
    /** 命中该 POI 的关键词/分类编码（分号分隔，§3.4） */
    @ExcelProperty("collect_keywords")
    private String collectKeywords;
    /** 采集时间（ISO-8601，§3.4） */
    @ExcelProperty("collected_at")
    private String collectedAt;

    /** 采集条目 → Excel 行 */
    public static RawPoiExcelRow of(RawPoi poi, String collectKeywords, String collectedAt) {
        RawPoiExcelRow row = new RawPoiExcelRow();
        row.amapId = poi.getAmapId();
        row.name = poi.getName();
        row.type = poi.getType();
        row.typecode = poi.getTypecode();
        row.keytag = poi.getKeytag();
        row.level = poi.getLevel();
        row.rating = poi.getRating();
        row.openTime = poi.getOpenTime();
        row.opentime2 = poi.getOpentime2();
        row.cost = poi.getCost();
        row.tel = poi.getTel();
        row.address = poi.getAddress();
        row.location = poi.getLocation();
        row.pname = poi.getPname();
        row.cityname = poi.getCityname();
        row.adname = poi.getAdname();
        row.photos = poi.getPhotos();
        row.collectKeywords = collectKeywords;
        row.collectedAt = collectedAt;
        return row;
    }
}
