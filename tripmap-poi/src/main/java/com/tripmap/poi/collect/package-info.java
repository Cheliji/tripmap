/**
 * 功能一：高德批量采集（设计说明书 §3）。
 *
 * <p>职责：关键词矩阵（resources/keywords/ 资源文件维护）、
 * 按 typecode 白名单 + 分区调用高德 place/text 分页拉取、
 * amap_id 去重、生成 {@code {city}-poi-raw.xlsx} 快照。</p>
 *
 * <p>入口：{@code CollectMain}（main 方法直跑，零 Spring）。
 * 运行示例：{@code java com.tripmap.poi.collect.CollectMain --city 杭州}</p>
 */
package com.tripmap.poi.collect;
