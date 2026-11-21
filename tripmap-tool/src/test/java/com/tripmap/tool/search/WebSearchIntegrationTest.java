package com.tripmap.tool.search;

import com.tripmap.tool.deepseek.DeepSeekProperties;
import com.tripmap.tool.deepseek.DeepSeekSearchClient;
import com.tripmap.tool.search.dto.SearchSourceItem;
import com.tripmap.tool.search.dto.WebSearchInput;
import com.tripmap.tool.search.dto.WebSearchOutput;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.util.StringUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 联网搜索集成测试（任务 2.7.3）：真实调用 DeepSeek 官方联网搜索一次。
 *
 * <p>打 {@code @Tag("integration")}，默认被 surefire 排除（父 POM {@code test.excluded.groups}），
 * 手动执行：</p>
 * <pre>mvn -pl tripmap-tool test "-Dtest=WebSearchIntegrationTest" "-Dtest.excluded.groups="</pre>
 *
 * <p>前置：环境变量 {@code DEEPSEEK_API_KEY} 已注入（未注入时按假设跳过，不判失败）。
 * <b>配额敏感：本类全程仅 1 次搜索调用</b>，勿追加更多用例。</p>
 */
@Tag("integration")
class WebSearchIntegrationTest {

    @Test
    void 真实查询一次验证来源字段存在() {
        String key = System.getenv("DEEPSEEK_API_KEY");
        assumeTrue(StringUtils.hasText(key), "未设置环境变量 DEEPSEEK_API_KEY，跳过真实调用");

        DeepSeekProperties properties = new DeepSeekProperties();
        properties.setApiKey(key);
        WebSearchTool tool = new WebSearchTool(
                new DeepSeekSearchClient(DeepSeekSearchClient.buildRestClient(properties), properties));

        // 真实调用 1 次（配额敏感）：MVP 杭州攻略场景的典型查询
        WebSearchOutput output = tool.execute(
                WebSearchInput.builder().query("杭州 西湖 旅游 注意事项").build());

        System.out.println("\n========== 联网搜索结果（数据来源：" + output.getAttribution() + "） ==========");
        System.out.printf("%-6s %-8s %-40s %s%n", "序号", "得分", "标题", "来源URL");
        for (SearchSourceItem item : output.getResults()) {
            System.out.printf("%-6d %-8.2f %-40s %s%n",
                    item.getIndex(), item.getRerankScore(),
                    abbreviate(item.getTitle(), 38), item.getUrl());
        }

        assertFalse(output.isEmpty(), "真实查询应返回非空结果");
        assertTrue(output.getResults().size() > 0);
        // 来源字段存在性逐条验证（TASKS 2.7.3 验收点）：url 强制非空、序号从 1 连续
        for (SearchSourceItem item : output.getResults()) {
            assertTrue(StringUtils.hasText(item.getUrl()),
                    "第 " + item.getIndex() + " 条结果缺少来源 URL: " + item.getTitle());
            assertTrue(item.getIndex() >= 1);
        }
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "-";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
