package com.tripmap.poi.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import com.tripmap.poi.collect.RawPoi;

/**
 * raw Excel 行模型（任务 1.3.2，列定义见设计说明书 §3.4，2026-11-05 按真实响应修订）
 *
 * <p>= {@link RawPoi} 全部高德字段 + 采集侧两列（collect_keywords / collected_at）。
 * 列顺序与 §3.4 表严格一致，作为功能二增强 Excel 的原样保留底列。</p>
 */
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

    public RawPoiExcelRow() {
    }

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

    public String getAmapId() {
        return amapId;
    }

    public void setAmapId(String amapId) {
        this.amapId = amapId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getTypecode() {
        return typecode;
    }

    public void setTypecode(String typecode) {
        this.typecode = typecode;
    }

    public String getKeytag() {
        return keytag;
    }

    public void setKeytag(String keytag) {
        this.keytag = keytag;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getRating() {
        return rating;
    }

    public void setRating(String rating) {
        this.rating = rating;
    }

    public String getOpenTime() {
        return openTime;
    }

    public void setOpenTime(String openTime) {
        this.openTime = openTime;
    }

    public String getOpentime2() {
        return opentime2;
    }

    public void setOpentime2(String opentime2) {
        this.opentime2 = opentime2;
    }

    public String getCost() {
        return cost;
    }

    public void setCost(String cost) {
        this.cost = cost;
    }

    public String getTel() {
        return tel;
    }

    public void setTel(String tel) {
        this.tel = tel;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getPname() {
        return pname;
    }

    public void setPname(String pname) {
        this.pname = pname;
    }

    public String getCityname() {
        return cityname;
    }

    public void setCityname(String cityname) {
        this.cityname = cityname;
    }

    public String getAdname() {
        return adname;
    }

    public void setAdname(String adname) {
        this.adname = adname;
    }

    public String getPhotos() {
        return photos;
    }

    public void setPhotos(String photos) {
        this.photos = photos;
    }

    public String getCollectKeywords() {
        return collectKeywords;
    }

    public void setCollectKeywords(String collectKeywords) {
        this.collectKeywords = collectKeywords;
    }

    public String getCollectedAt() {
        return collectedAt;
    }

    public void setCollectedAt(String collectedAt) {
        this.collectedAt = collectedAt;
    }
}
