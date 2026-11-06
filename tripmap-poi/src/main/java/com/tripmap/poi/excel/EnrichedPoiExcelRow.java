package com.tripmap.poi.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import com.tripmap.poi.collect.RawPoi;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 增强 Excel 行模型（功能二，列定义见 tripmap-poi功能设计说明书 §4.10）
 *
 * <p>= raw 全部 19 列（原样保留，{@link RawPoiExcelRow}）+ 12 增强列。
 * 断点续跑的"原样照抄"语义依赖本类：SUCCESS 行按读回字符串值整体保留，
 * 不由 raw 重新生成（人工修改 reviewed/open_hours 等重跑时全部存活）。</p>
 *
 * <p>数值类增强字段（ticket_price/suggest_duration）以字符串落盘，
 * 与 raw 列保持一致（§3.4 约定存字符串不转数值）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class EnrichedPoiExcelRow extends RawPoiExcelRow {

    @ExcelProperty("district_adcode")
    private String districtAdcode;
    @ExcelProperty("category")
    private String category;
    @ExcelProperty("summary")
    private String summary;
    /** §4.6 JSON Schema；整列空 = 未知，合法 */
    @ExcelProperty("open_hours")
    private String openHours;
    /** §4.5 受控词表闭集，分号分隔 */
    @ExcelProperty("tags")
    private String tags;
    /** 门票（元），0=免费须有素材依据，可空 */
    @ExcelProperty("ticket_price")
    private String ticketPrice;
    /** 建议游玩时长（分钟），可空 */
    @ExcelProperty("suggest_duration")
    private String suggestDuration;
    /** 景点公众号名称，可空，禁止猜测拼合（§4.1 红线 3） */
    @ExcelProperty("official_account")
    private String officialAccount;
    /**
     * AI 生成字段声明（v1.4）：代码按固定模板组装，列出本行由 AI 生成的非空字段，
     * 供运行时 LLM 介绍景点/给建议时作时效性说明依据；不记录内容来源与 URL。
     */
    @ExcelProperty("remark")
    private String remark;
    /** 关键素材摘录（带 URL），供人工校对查证（§4.1 红线 4） */
    @ExcelProperty("search_snippets")
    private String searchSnippets;
    /** 全部引用来源 URL（分号分隔）；open_hours 非空时本列必须非空（§4.10） */
    @ExcelProperty("source_urls")
    private String sourceUrls;
    /** SUCCESS / FAILED（脚本写入） */
    @ExcelProperty("enrich_status")
    private String enrichStatus;
    /** 人工校对标记（y/n），入库前置条件（§4.11） */
    @ExcelProperty("reviewed")
    private String reviewed;

    /** 采集条目 → 待增强行（①②两步映射前的初始形态） */
    public static EnrichedPoiExcelRow from(RawPoi poi, String collectKeywords, String collectedAt) {
        return from(RawPoiExcelRow.of(poi, collectKeywords, collectedAt));
    }

    /** raw 行 → 待增强行：19 列原样拷贝，增强列留空 */
    public static EnrichedPoiExcelRow from(RawPoiExcelRow raw) {
        EnrichedPoiExcelRow row = new EnrichedPoiExcelRow();
        row.setAmapId(raw.getAmapId());
        row.setName(raw.getName());
        row.setType(raw.getType());
        row.setTypecode(raw.getTypecode());
        row.setKeytag(raw.getKeytag());
        row.setLevel(raw.getLevel());
        row.setRating(raw.getRating());
        row.setOpenTime(raw.getOpenTime());
        row.setOpentime2(raw.getOpentime2());
        row.setCost(raw.getCost());
        row.setTel(raw.getTel());
        row.setAddress(raw.getAddress());
        row.setLocation(raw.getLocation());
        row.setPname(raw.getPname());
        row.setCityname(raw.getCityname());
        row.setAdname(raw.getAdname());
        row.setPhotos(raw.getPhotos());
        row.setCollectKeywords(raw.getCollectKeywords());
        row.setCollectedAt(raw.getCollectedAt());
        return row;
    }
}
