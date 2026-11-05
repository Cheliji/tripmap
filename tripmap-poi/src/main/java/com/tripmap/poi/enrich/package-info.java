/**
 * 功能二：联网搜索 + LLM 增强（设计说明书 §4，待实现）。
 *
 * <p>职责：逐条读取 raw Excel，DeepSeek 联网搜索取证
 *（{@code POST https://api.deepseek.com/v1/tools/search}）+ DeepSeek LLM
 * 结构化整理（分类/描述/open_hours 草稿），生成 enriched Excel。</p>
 *
 * <p>红线：LLM 仅允许从搜索素材中提取归纳，事实字段必须带 source URL
 *（AGENTS.md 核心原则 2；设计说明书 §4.1）。</p>
 */
package com.tripmap.poi.enrich;
