/**
 * 功能三：enriched Excel → SQL 导入文件生成（功能设计说明书 §5.1，v1.5）。
 *
 * <p>职责：仅导出 {@code enrich_status=SUCCESS} 的行（人工校对列 reviewed 保留在 Excel
 * 但不再是门槛），映射校验后生成 {@code INSERT ... ON DUPLICATE KEY UPDATE} 的 .sql 文件，
 * 由人工在 MySQL 客户端执行。<b>零数据库依赖</b>（无 JDBC/驱动）——poi 表唯一写入方
 * 是本管道产出的 SQL（单写入者架构，§4.6 防线 2），表 DDL 见 {@code sql/001_poi.sql}。</p>
 *
 * <p>包内分工：{@link PoiImportRowMapper} 映射+导入前校验（与功能二共用
 * {@code OpenHoursValidator}）；{@link PoiInsertSqlBuilder} 语句生成（转义+方言注记）；
 * {@link ImportMain} 入口（过滤/去重/统计/dry-run）。</p>
 */
package com.tripmap.poi.importdb;
