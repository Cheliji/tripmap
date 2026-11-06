/**
 * Excel 行模型与读写（tripmap-poi功能设计说明书 §3.4 / §4.10）。
 *
 * <p>职责：{@link com.tripmap.poi.excel.RawPoiExcelRow}（高德原始字段快照，19 列）、
 * {@link com.tripmap.poi.excel.EnrichedPoiExcelRow}（= raw 19 列原样保留 + 12 增强列）。
 * 列定义以设计说明书为准，不得擅自增删。EasyExcel 读写与断点续跑的快照写出
 * 见 {@link com.tripmap.poi.enrich.EnrichMain}。</p>
 */
package com.tripmap.poi.excel;
