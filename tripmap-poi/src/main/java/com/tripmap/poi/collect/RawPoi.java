package com.tripmap.poi.collect;

/**
 * 高德 POI 原始记录（任务 1.1.3）
 *
 * <p>对应设计说明书 §3.4 raw Excel 列定义（高德字段快照），
 * 字段口径严格对齐实测响应：{@code biz_ext} 携带 rating/open_time/opentime2/level/cost；
 * 响应中无 adcode/pcode，区县级信息只有 adname；
 * {@code parent}/{@code distance}/{@code importance} 等恒为空字段不采集。
 * {@code collect_keywords}/{@code collected_at} 两列由采集脚本（1.3.2）在快照组装时补充，本类不含。</p>
 *
 * <p>采用 JavaBean 形态（非 record）：下游 Excel 读写（EasyExcel）与去重合并需要可变对象。</p>
 */
public class RawPoi {

    /** amap_id：高德 id，快照内天然主键（§3.3 去重依据） */
    private String amapId;
    /** POI 名称 */
    private String name;
    /** 原始分类（分号多段，如"风景名胜;公园广场;公园"） */
    private String type;
    /** 分类编码，可能多值（竖线分隔，如"080501|060000"），原样保留 */
    private String typecode;
    /** 特征标签（如"5A景区"，可空） */
    private String keytag;
    /** 景区等级（biz_ext.level，AAAAA 等，可空） */
    private String level;
    /** 高德评分（biz_ext.rating，可空） */
    private String rating;
    /** 营业时间简式（biz_ext.open_time，"09:00-17:00"/"24小时营业"，可空） */
    private String openTime;
    /** 营业时间全式（biz_ext.opentime2，含闭馆日说明，功能二 open_hours 的底稿，可空） */
    private String opentime2;
    /** 人均消费（biz_ext.cost，可空） */
    private String cost;
    /** 电话（可空） */
    private String tel;
    /** 地址 */
    private String address;
    /** "lng,lat"，GCJ-02 坐标系，存字符串不转数值（§3.4） */
    private String location;
    /** 省名 */
    private String pname;
    /** 市名 */
    private String cityname;
    /** 区县名（响应无 adcode，仅名称） */
    private String adname;
    /** 图片 URL（photos[].url 分号拼接，可空） */
    private String photos;

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

    @Override
    public String toString() {
        return "RawPoi{" + "amapId='" + amapId + '\'' + ", name='" + name + '\''
                + ", typecode='" + typecode + '\'' + ", adname='" + adname + '\'' + '}';
    }
}
