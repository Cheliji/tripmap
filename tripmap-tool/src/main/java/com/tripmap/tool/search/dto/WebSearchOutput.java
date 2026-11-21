package com.tripmap.tool.search.dto;

import com.tripmap.tool.spi.ToolOutput;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 联网搜索工具输出 DTO（任务 2.7）。
 *
 * <p>{@code results} 顺序与官方返回一致（工具层不重排），{@code index}
 * 从 1 连续编号；空结果不是失败（官方口径：结果为空不计费），以
 * {@code empty=true} 表达，由攻略节点走降级文案，模型不得凭空补内容。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebSearchOutput implements ToolOutput {

    /** 查询词（回显输入） */
    private String query;

    /** 带来源的结果列表（序号从 1 开始；无 url 条目已被丢弃） */
    private List<SearchSourceItem> results;

    /** 空结果标记：true 表示无任何有效来源（上游应降级，不得编造） */
    private boolean empty;

    /** 数据来源归因（展示合规） */
    private String attribution;
}
