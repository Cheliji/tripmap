package com.tripmap.tool.search;

import com.tripmap.common.exception.BizException;
import com.tripmap.tool.deepseek.DeepSeekProperties;
import com.tripmap.tool.deepseek.DeepSeekSearchClient;
import com.tripmap.tool.search.dto.SearchSourceItem;
import com.tripmap.tool.search.dto.WebSearchInput;
import com.tripmap.tool.search.dto.WebSearchOutput;
import com.tripmap.tool.spi.ToolNames;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 联网搜索工具单测（任务 2.7.1）。
 *
 * <p>MockWebServer 打桩薄客户端，验证：参数校验（缺失/超长）、
 * 结果映射（index 从 1 连续编号）、强制带来源（无 url 条目丢弃）、
 * 空结果降级标记。日志输出结果表（AGENTS.md 测试定规）。</p>
 */
class WebSearchToolTest {

    private MockWebServer server;
    private WebSearchTool tool;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        DeepSeekProperties properties = new DeepSeekProperties();
        properties.setApiKey("test-key");
        properties.setBaseUrl(server.url("/").toString().replaceAll("/$", ""));
        properties.setConnectTimeoutMs(1000);
        properties.setSearchReadTimeoutMs(1000);
        properties.setMaxRetries(0);
        properties.setSearchMinIntervalMs(0);
        tool = new WebSearchTool(new DeepSeekSearchClient(
                DeepSeekSearchClient.buildRestClient(properties), properties));
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void 工具名与契约() {
        System.out.println("[场景] 工具名埋点应取 ToolNames.WEB_SEARCH");
        System.out.println("[输入] tool.name()");
        System.out.println("[输出] name=" + tool.name());
        assertEquals(ToolNames.WEB_SEARCH, tool.name());
    }

    @Test
    void 正常结果映射index从1连续编号() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .setBody("""
                {"results": [
                  {"title": "西湖官网", "url": "https://a.com/x", "summary": "摘要A",
                   "content": "正文A", "rerank_score": 2.1},
                  {"title": "杭州攻略", "url": "https://b.com/y", "rerank_score": 0.5}
                ]}"""));

        System.out.println("[场景] 两条有效结果 → index 应从 1 连续编号，empty=false");
        WebSearchOutput output = tool.execute(WebSearchInput.builder().query("杭州 西湖").build());

        System.out.println("---------- 工具输出（人读核对） ----------");
        System.out.printf("%-6s %-8s %-20s %s%n", "index", "score", "title", "url");
        for (SearchSourceItem item : output.getResults()) {
            System.out.printf("%-6d %-8.2f %-20s %s%n",
                    item.getIndex(), item.getRerankScore(), item.getTitle(), item.getUrl());
        }
        System.out.println("empty=" + output.isEmpty() + ", attribution=" + output.getAttribution());

        assertEquals(2, output.getResults().size());
        assertEquals(1, output.getResults().get(0).getIndex());
        assertEquals(2, output.getResults().get(1).getIndex());
        assertEquals("https://a.com/x", output.getResults().get(0).getUrl());
        assertEquals("正文A", output.getResults().get(0).getContent());
        assertFalse(output.isEmpty());
        assertEquals("杭州 西湖", output.getQuery());
    }

    @Test
    void 无url条目被强制丢弃且序号仍连续() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .setBody("""
                {"results": [
                  {"title": "有来源", "url": "https://a.com/1", "rerank_score": 3.0},
                  {"title": "无来源条目", "summary": "无url应被丢弃", "rerank_score": 9.9},
                  {"title": "也有来源", "url": "https://c.com/3", "rerank_score": 1.0}
                ]}"""));

        System.out.println("[场景] 中间条目无 url → 应被丢弃；存活条目标题对应官方高分条，index 仍 1/2 连续");
        WebSearchOutput output = tool.execute(WebSearchInput.builder().query("q").build());

        System.out.println("---------- 工具输出（人读核对） ----------");
        for (SearchSourceItem item : output.getResults()) {
            System.out.printf("index=%d title=%s url=%s%n", item.getIndex(), item.getTitle(), item.getUrl());
        }
        System.out.println("empty=" + output.isEmpty() + "（丢弃 1 条无 url，剩 2 条）");

        assertEquals(2, output.getResults().size());
        assertEquals("有来源", output.getResults().get(0).getTitle());
        assertEquals("也有来源", output.getResults().get(1).getTitle());
        assertEquals(1, output.getResults().get(0).getIndex());
        assertEquals(2, output.getResults().get(1).getIndex());
        assertFalse(output.isEmpty());
    }

    @Test
    void 全部条目无url视为空结果() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .setBody("""
                {"results": [
                  {"title": "无来源甲", "rerank_score": 1.0},
                  {"title": "无来源乙", "rerank_score": 0.5}
                ]}"""));

        System.out.println("[场景] 全部条目无 url → 强制带来源后为空，empty=true（上游降级，不报错）");
        WebSearchOutput output = tool.execute(WebSearchInput.builder().query("q").build());

        System.out.println("结果条数=" + output.getResults().size() + ", empty=" + output.isEmpty());
        assertTrue(output.getResults().isEmpty());
        assertTrue(output.isEmpty());
    }

    @Test
    void 官方空结果返回empty不算失败() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"results\": []}"));

        System.out.println("[场景] 官方返回空数组 → 空结果不算失败（官方口径：不计费）");
        WebSearchOutput output = tool.execute(WebSearchInput.builder().query("查无此地xyz").build());
        System.out.println("结果条数=" + output.getResults().size() + ", empty=" + output.isEmpty());
        assertTrue(output.isEmpty());
    }

    @Test
    void 缺query抛参数缺失() {
        System.out.println("[场景] query 为空白 → BizException(PARAM_MISSING=100002)");
        BizException e = assertThrows(BizException.class,
                () -> tool.execute(WebSearchInput.builder().query(" ").build()));
        System.out.println("[输出] code=" + e.getCode() + ", message=" + e.getMessage());
        assertEquals(100002, e.getCode());
    }

    @Test
    void query超长拒绝() {
        String longQuery = "杭".repeat(200);
        System.out.println("[场景] query 200 字符 > 官方上限 128 → BizException(100001)，不发请求");
        BizException e = assertThrows(BizException.class,
                () -> tool.execute(WebSearchInput.builder().query(longQuery).build()));
        System.out.println("[输出] code=" + e.getCode() + ", message=" + e.getMessage());
        assertEquals(100001, e.getCode());
        assertEquals(0, server.getRequestCount(), "参数校验失败不应发出 HTTP 请求");
    }

    @Test
    void 响应缺results数组视为外部服务失败() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"data\": 1}"));
        System.out.println("[场景] 响应非法（缺 results 数组）→ ExternalApiException(400003)，不重试");
        com.tripmap.common.exception.ExternalApiException e = assertThrows(
                com.tripmap.common.exception.ExternalApiException.class,
                () -> tool.execute(WebSearchInput.builder().query("q").build()));
        System.out.println("[输出] code=" + e.getCode() + ", retryable=" + e.isRetryable());
        assertEquals(400003, e.getCode());
        assertFalse(e.isRetryable());
    }

    @Test
    void summary与content允许为空() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .setBody("""
                {"results": [
                  {"title": "仅标题和url", "url": "https://a.com/x"}
                ]}"""));
        List<SearchSourceItem> results = tool.execute(WebSearchInput.builder().query("q").build()).getResults();
        System.out.println("[场景] 条目缺 summary/content/rerank_score → 映射为 null/0.0 不报错");
        System.out.printf("[输出] title=%s summary=%s content=%s score=%.2f%n",
                results.get(0).getTitle(), results.get(0).getSummary(),
                results.get(0).getContent(), results.get(0).getRerankScore());
        assertEquals(1, results.size());
        assertNull(results.get(0).getSummary());
        assertNull(results.get(0).getContent());
        assertEquals(0.0, results.get(0).getRerankScore());
    }
}
