package com.tripmap.common.dto.tool;

/**
 * 工具输出 DTO 标记接口（任务 2.1.1）。
 *
 * <p>工具输出是事实层数据：下游编排节点与前端（经 Result 包装）共用同一份结构，
 * 保证"工具产出 → 节点消费 → 结果页渲染"形状一致，各层不各自造结构。</p>
 */
public interface ToolOutput {
}
