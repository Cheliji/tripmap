package com.tripmap.tool.amap;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.util.StringUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 高德客户端集成测试（任务 1.1.4）：真实调用高德开放平台 1 次。
 *
 * <p>打 {@code @Tag("integration")}，默认被 surefire 排除（父 POM {@code test.excluded.groups}），
 * 手动执行：</p>
 * <pre>mvn -pl tripmap-tool test "-Dtest=AmapClientIntegrationTest" "-Dtest.excluded.groups="</pre>
 *
 * <p>前置：环境变量 {@code AMAP_API_KEY} 已注入（未注入时按假设跳过，不判失败）。</p>
 */
@Tag("integration")
class AmapClientIntegrationTest {

    @Test
    void placeText真实调用返回西湖POI() {
        String apiKey = System.getenv("AMAP_API_KEY");
        assumeTrue(StringUtils.hasText(apiKey), "未设置环境变量 AMAP_API_KEY，跳过真实调用");

        AmapProperties properties = new AmapProperties();
        properties.setApiKey(apiKey);
        AmapClient client = new AmapClient(AmapClient.buildRestClient(properties), properties);

        // 真实调用 1 次：杭州 + 西湖关键词（配额敏感，勿在此测试内追加更多调用）
        JsonNode root = client.get("/v3/place/text",
                Map.of("keywords", "西湖", "city", "杭州", "citylimit", "true", "offset", "5"));

        assertEquals("1", root.path("status").asText(), "高德业务状态应为 1: " + root.path("info").asText());
        JsonNode pois = root.path("pois");
        assertTrue(pois.isArray() && !pois.isEmpty(), "西湖/杭州查询应返回非空 POI 列表");
        JsonNode first = pois.get(0);
        assertFalse(first.path("id").asText().isBlank(), "POI 应携带 id");
        assertFalse(first.path("name").asText().isBlank(), "POI 应携带 name");
        assertFalse(first.path("location").asText().isBlank(), "POI 应携带 GCJ-02 坐标");
    }
}
