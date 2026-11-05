/**
 * 功能三：Excel 导入数据库（设计说明书 §5，待实现）。
 *
 * <p>职责：仅导入 {@code reviewed=y} 的行，amap_id 幂等 upsert 至 MySQL
 * {@code poi} 表（原始 JDBC，不借用运行时 ORM）。前置依赖：任务 1.2.1 的
 * poi 表 DDL 落地。</p>
 */
package com.tripmap.poi.importdb;
