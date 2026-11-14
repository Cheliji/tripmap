package com.tripmap.tool.spi;

import com.tripmap.common.dto.tool.ToolInput;
import com.tripmap.common.dto.tool.ToolOutput;
import com.tripmap.common.exception.BizException;
import com.tripmap.common.exception.ExternalApiException;

/**
 * 工具统一接口（任务 2.1.1，设计说明书 §9 工具层规格）。
 *
 * <p>tripmap 六个工具（POI 检索/天气查询/通勤矩阵/空间聚类/路径求解/联网搜索）
 * 全部实现本接口，向编排层呈现同一种形状：编排节点（M3 StateGraph）按接口调用，
 * 不感知实现背后是本地算法还是外部 API。</p>
 *
 * <p><b>调用约定</b>：工具由编排节点以确定性代码调用，不走 LLM function calling——
 * 何时调、参数是什么由流程图决定，模型无权干预（AGENTS.md §1 原则 1/2；
 * 设计说明书 §2.2：不做 MCP，直接使用框架函数调用）。</p>
 *
 * <p><b>异常约定</b>：</p>
 * <ul>
 *   <li>预期内业务失败（如无解、参数不合法、全部排列不可行）抛 {@link BizException}，
 *       由节点走 §7.3 回退路由；</li>
 *   <li>外部 API 失败抛 {@link ExternalApiException}（携带 retryable 标记），
 *       限次重试后仍失败由节点降级；</li>
 *   <li>禁止吞异常、禁止返回 null 表示失败；禁止在工具内部使用统一返回体
 *       {@code Result} 包装（Result 仅属于 Controller 层）。</li>
 * </ul>
 *
 * @param <I> 输入 DTO，必须继承 {@link ToolInput}
 * @param <O> 输出 DTO，必须继承 {@link ToolOutput}
 */
public interface TripTool<I extends ToolInput, O extends ToolOutput> {

    /**
     * 工具名：节点日志埋点、SSE 进度事件与评测路由的统一标识，
     * 取值见 {@link ToolNames}。
     */
    String name();

    /**
     * 执行工具。
     *
     * @param input 装配完整的输入 DTO（参数由编排节点从图 State 确定性装配，非模型生成）
     * @return 输出 DTO，禁止返回 null
     * @throws BizException         预期内业务失败
     * @throws ExternalApiException 外部 API 失败（带 retryable 标记）
     */
    O execute(I input);
}
