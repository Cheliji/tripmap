package com.tripmap.tool.search;

import com.tripmap.common.exception.BizException;
import com.tripmap.common.result.ResultCode;
import com.tripmap.tool.deepseek.DeepSeekSearchClient;
import com.tripmap.tool.search.dto.SearchSourceItem;
import com.tripmap.tool.search.dto.WebSearchInput;
import com.tripmap.tool.search.dto.WebSearchOutput;
import com.tripmap.tool.spi.ToolNames;
import com.tripmap.tool.spi.TripTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 联网搜索工具（任务 2.7，设计说明书 §9 工具层 T6）。
 *
 * <p><b>职责</b>：把 DeepSeek 官方联网搜索结果包装为工具契约形状，
 * 供攻略类节点（城市概览、注意事项、攻略素材）消费——叙述性内容的唯一
 * 事实供给通道（MVP 不做知识库/RAG，设计说明书 §2.2）。</p>
 *
 * <p><b>强制带来源</b>（TASKS 2.7.1）：结果条目必须携带 url 方可进入
 * 素材上下文，无 url 条目丢弃并 warn——无法溯源的内容不允许流向 LLM。
 * 空结果不算失败（官方口径：不计费），返回 {@code empty=true} 交上游降级，
 * 模型不得凭空补内容（AGENTS.md 第 1 节原则 2）。</p>
 *
 * <p><b>引用契约</b>：输出 {@code index} 从 1 编号，攻略节点输出中的
 * 引用标记 {@code [n]} 即对应此序号；"无来源事实句删除"后处理
 * （{@code UncitedFactSentenceFilter}，TASKS 2.7.2）按此核对。</p>
 */
@Component
public class WebSearchTool implements TripTool<WebSearchInput, WebSearchOutput> {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);

    /** 查询词长度上限（DeepSeek 官方限制 128 字符） */
    private static final int QUERY_MAX_LENGTH = 128;

    /** 数据来源归因（展示合规） */
    private static final String ATTRIBUTION = "DeepSeek 联网搜索";

    private final DeepSeekSearchClient client;

    public WebSearchTool(DeepSeekSearchClient client) {
        this.client = client;
    }

    @Override
    public String name() {
        return ToolNames.WEB_SEARCH;
    }

    @Override
    public WebSearchOutput execute(WebSearchInput input) {
        validate(input);
        String query = input.getQuery().trim();
        log.info("联网搜索: query=\"{}\"", query);

        List<DeepSeekSearchClient.SearchResultItem> raw = client.search(query);

        // 强制带来源：无 url 条目无法溯源，丢弃并 warn
        List<SearchSourceItem> results = new ArrayList<>();
        int dropped = 0;
        for (DeepSeekSearchClient.SearchResultItem item : raw) {
            if (!StringUtils.hasText(item.url())) {
                dropped++;
                log.warn("丢弃无来源 URL 的搜索结果条目: title=\"{}\"", item.title());
                continue;
            }
            results.add(SearchSourceItem.builder()
                    .index(results.size() + 1)
                    .title(item.title())
                    .url(item.url())
                    .summary(item.summary())
                    .content(item.content())
                    .rerankScore(item.rerankScore())
                    .build());
        }
        if (dropped > 0) {
            log.warn("联网搜索丢弃无来源条目 {} 条（强制带来源，TASKS 2.7.1）", dropped);
        }
        printResult(query, results, dropped);

        return WebSearchOutput.builder()
                .query(query)
                .results(results)
                .empty(results.isEmpty())
                .attribution(ATTRIBUTION)
                .build();
    }

    // ---------- 校验 ----------

    private void validate(WebSearchInput input) {
        if (input == null || !StringUtils.hasText(input.getQuery())) {
            throw new BizException(ResultCode.PARAM_MISSING, "缺少必填参数: query");
        }
        if (input.getQuery().trim().length() > QUERY_MAX_LENGTH) {
            throw new BizException(ResultCode.PARAM_VALIDATION_FAILED,
                    "query 超长（上限 " + QUERY_MAX_LENGTH + " 字符）: " + input.getQuery().trim().length());
        }
    }

    // ---------- 日志 ----------

    private void printResult(String query, List<SearchSourceItem> results, int dropped) {
        StringBuilder sb = new StringBuilder("\n---------- 联网搜索结果 query=\"").append(query).append("\" ----------\n");
        sb.append(String.format("共 %d 条（丢弃无来源 %d 条）%n", results.size(), dropped));
        sb.append(String.format("%-6s %-8s %-30s %s%n", "序号", "得分", "标题", "来源URL"));
        for (SearchSourceItem item : results) {
            sb.append(String.format("%-6d %-8.2f %-30s %s%n",
                    item.getIndex(), item.getRerankScore(),
                    abbreviate(item.getTitle(), 28), item.getUrl()));
        }
        log.info(sb.toString());
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "-";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
