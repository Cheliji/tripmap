/**
 * Excel 行模型与读写（设计说明书 §3.4 / §4.4）。
 *
 * <p>职责：{@code PoiRawRecord}（高德原始字段快照）、
 * {@code PoiEnrichedRecord}（增强列）、{@code PoiExcelStore}
 *（EasyExcel 读写、断点续跑判定）。列定义以设计说明书为准，不得擅自增删。</p>
 */
package com.tripmap.poi.excel;
