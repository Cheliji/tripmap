package com.tripmap.common.dto.tool;

/**
 * 工具输入 DTO 标记接口（任务 2.1.1）。
 *
 * <p>tripmap 六个工具（设计说明书 §9）的输入 DTO 必须实现本接口，
 * 编译期将"工具契约"与领域无关的任意对象区分开；
 * 输入一律为强类型 DTO，由编排节点从图 State（背景 Schema）装配，
 * 不接收自然语言原文（AGENTS.md §1：事实全部来自工具）。</p>
 */
public interface ToolInput {
}
