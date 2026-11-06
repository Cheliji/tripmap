/**
 * 功能二：联网搜索 + LLM 增强（tripmap-poi功能设计说明书 §4，v1.3 定稿）。
 *
 * <p>职责：逐条读取 raw Excel，DeepSeek 联网搜索取证
 *（{@code POST https://api.deepseek.com/v1/tools/search}）+ DeepSeek LLM（flash）
 * 一次调用产出 summary/open_hours/tags/ticket_price/suggest_duration/official_account，
 * 生成 enriched Excel；district_adcode 与 category 由静态映射/规则映射产出（不经 LLM）。</p>
 *
 * <p>红线：LLM 是"整理器"不是"事实源"，仅允许从搜索素材与高德字段中提取归纳，
 * 素材无依据一律 null、禁止无中生有，事实字段必须带 source URL
 *（AGENTS.md 核心原则 2；§4.1）。断点续跑：输出 Excel 即检查点，
 * SUCCESS 行冻结原样保留，FAILED/空缺行重跑覆盖（见 {@link EnrichMain}）。</p>
 */
package com.tripmap.poi.enrich;
