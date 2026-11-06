package com.tripmap.poi.enrich;

import com.tripmap.common.exception.ExternalApiException;
import com.tripmap.common.result.ResultCode;
import com.tripmap.poi.excel.EnrichedPoiExcelRow;
import com.tripmap.poi.excel.RawPoiExcelRow;
import com.tripmap.tool.deepseek.DeepSeekChatClient;
import com.tripmap.tool.deepseek.DeepSeekSearchClient;
import com.tripmap.tool.deepseek.DeepSeekSearchClient.SearchResultItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单条五步流水线单测（tripmap-poi功能设计说明书 §4.2）
 *
 * <p>搜索/LLM 客户端打桩，覆盖：成功路径（字段组装/映射/素材落盘）、
 * ①②映射未命中仍 SUCCESS、搜索失败/LLM 失败/解析失败/校验失败四类 FAILED 分支、
 * 交叉校验（open_hours 非空 ⇒ source_urls 非空）、组合查询口径。</p>
 */
class RowEnricherTest {

    private DeepSeekSearchClient searchClient;
    private DeepSeekChatClient chatClient;
    private RowEnricher enricher;

    private static final String VALID_LLM_JSON = """
            {"summary": "宋代园林，以红鱼池闻名",
             "open_hours": {"mon": [{"open": "09:00", "close": "17:00"}], "tue": [], "wed": null,
               "thu": null, "fri": null, "sat": null, "sun": null},
             "tags": ["园林景观"],
             "ticket_price": 45,
             "suggest_duration": 90,
             "official_account": null}""";

    @BeforeEach
    void setUp() {
        searchClient = mock(DeepSeekSearchClient.class);
        chatClient = mock(DeepSeekChatClient.class);
        // 注入固定 after 日期，保证查询词可断言
        enricher = new RowEnricher(searchClient, chatClient,
                DistrictAdcodeMapping.loadDefault(), CategoryMapping.loadDefault(),
                java.time.LocalDate.of(2025, 11, 6));
    }

    private RawPoiExcelRow rawRow() {
        return RawPoiExcelRow.builder()
                .amapId("B000001")
                .name("花港观鱼")
                .cityname("杭州市")
                .adname("西湖区")
                .typecode("110101")
                .opentime2("周一至周日 09:00-17:00")
                .build();
    }

    private void stubSearchWithMaterial() {
        when(searchClient.search(anyString())).thenReturn(List.of(
                new SearchResultItem("官网", "https://a.com/1", "摘录一", null, 2.0)));
    }

    @Test
    void 成功路径字段全部组装() {
        stubSearchWithMaterial();
        when(chatClient.chatJson(anyString(), anyString())).thenReturn(VALID_LLM_JSON);

        RowEnricher.RowOutcome outcome = enricher.enrich(rawRow());

        assertTrue(outcome.success());
        EnrichedPoiExcelRow row = outcome.row();
        assertEquals("SUCCESS", row.getEnrichStatus());
        assertEquals("330106", row.getDistrictAdcode(), "西湖区 → 330106");
        assertEquals("公园乐园", row.getCategory(), "110101 → 公园乐园");
        assertEquals("宋代园林，以红鱼池闻名", row.getSummary());
        assertTrue(row.getOpenHours().contains("\"mon\":[{"));
        assertEquals("园林景观", row.getTags());
        assertEquals("45", row.getTicketPrice());
        assertEquals("90", row.getSuggestDuration());
        assertNull(row.getOfficialAccount());
        assertTrue(row.getSearchSnippets().contains("[1] 官网"));
        assertEquals("https://a.com/1", row.getSourceUrls());
        assertEquals("花港观鱼", row.getName(), "raw 19 列原样保留");
    }

    @Test
    void 组合查询口径为名称加城市加固定后缀加近一年过滤() {
        stubSearchWithMaterial();
        when(chatClient.chatJson(anyString(), anyString())).thenReturn(VALID_LLM_JSON);
        enricher.enrich(rawRow());

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(searchClient).search(queryCaptor.capture());
        assertEquals("花港观鱼 杭州市 开放时间 门票 游玩攻略 after:2025-11-06", queryCaptor.getValue());

        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatClient).chatJson(anyString(), userCaptor.capture());
        assertTrue(userCaptor.getValue().contains("花港观鱼"), "用户消息应含高德字段");
        assertTrue(userCaptor.getValue().contains("[1] 官网"), "用户消息应含编号素材");
    }

    @Test
    void 超长名称截断时保留完整after操作符() {
        RawPoiExcelRow raw = rawRow();
        raw.setName("名字".repeat(60)); // 60*2=120 字符，超出 128 上限
        stubSearchWithMaterial();
        when(chatClient.chatJson(anyString(), anyString())).thenReturn(VALID_LLM_JSON);
        enricher.enrich(raw);

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(searchClient).search(queryCaptor.capture());
        String query = queryCaptor.getValue();
        org.junit.jupiter.api.Assertions.assertTrue(query.length() <= 128, "超上限: " + query.length());
        org.junit.jupiter.api.Assertions.assertTrue(query.endsWith("after:2025-11-06"),
                "after 操作符必须完整保留（截断会静默失效）: " + query);
    }

    @Test
    void 映射未命中仍判SUCCESS仅留空() {
        RawPoiExcelRow raw = rawRow();
        raw.setAdname("不存在的区");
        raw.setTypecode("990000");
        stubSearchWithMaterial();
        when(chatClient.chatJson(anyString(), anyString())).thenReturn(VALID_LLM_JSON);

        RowEnricher.RowOutcome outcome = enricher.enrich(raw);

        assertTrue(outcome.success());
        assertNull(outcome.row().getDistrictAdcode());
        assertNull(outcome.row().getCategory());
    }

    @Test
    void 搜索失败标FAILED步骤SEARCH() {
        when(searchClient.search(anyString()))
                .thenThrow(new ExternalApiException(ResultCode.EXTERNAL_SEARCH, "DeepSeek", true));

        RowEnricher.RowOutcome outcome = enricher.enrich(rawRow());

        assertEquals("FAILED", outcome.row().getEnrichStatus());
        assertEquals("SEARCH", outcome.failStep());
        assertTrue(outcome.failReason() != null && !outcome.failReason().isEmpty());
    }

    @Test
    void LLM调用失败标FAILED步骤LLM() {
        stubSearchWithMaterial();
        when(chatClient.chatJson(anyString(), anyString()))
                .thenThrow(new ExternalApiException(ResultCode.EXTERNAL_LLM, "DeepSeek", true));

        RowEnricher.RowOutcome outcome = enricher.enrich(rawRow());

        assertEquals("FAILED", outcome.row().getEnrichStatus());
        assertEquals("LLM", outcome.failStep());
    }

    @Test
    void LLM输出解析失败标FAILED步骤LLM() {
        stubSearchWithMaterial();
        when(chatClient.chatJson(anyString(), anyString())).thenReturn("not-json");

        RowEnricher.RowOutcome outcome = enricher.enrich(rawRow());

        assertEquals("FAILED", outcome.row().getEnrichStatus());
        assertEquals("LLM", outcome.failStep());
    }

    @Test
    void tags越界受控词表标FAILED步骤VALIDATE() {
        stubSearchWithMaterial();
        when(chatClient.chatJson(anyString(), anyString())).thenReturn(
                "{\"summary\": \"s\", \"tags\": [\"绝美\"], \"open_hours\": null}");

        RowEnricher.RowOutcome outcome = enricher.enrich(rawRow());

        assertEquals("FAILED", outcome.row().getEnrichStatus());
        assertEquals("VALIDATE", outcome.failStep());
        assertTrue(outcome.failReason().contains("绝美"));
    }

    @Test
    void open_hours半成品JSON标FAILED不落盘() {
        stubSearchWithMaterial();
        when(chatClient.chatJson(anyString(), anyString())).thenReturn(
                "{\"summary\": \"s\", \"tags\": [], \"open_hours\": {\"mon\": [{\"open\": \"9点\"}]}}");

        RowEnricher.RowOutcome outcome = enricher.enrich(rawRow());

        assertEquals("FAILED", outcome.row().getEnrichStatus());
        assertEquals("VALIDATE", outcome.failStep());
        assertNull(outcome.row().getOpenHours(), "半成品 open_hours 不落盘（§4.2）");
    }

    @Test
    void open_hours非空但无来源URL标FAILED交叉校验() {
        when(searchClient.search(anyString())).thenReturn(List.of()); // 搜索成功但无结果
        when(chatClient.chatJson(anyString(), anyString())).thenReturn(VALID_LLM_JSON);

        RowEnricher.RowOutcome outcome = enricher.enrich(rawRow());

        assertEquals("FAILED", outcome.row().getEnrichStatus());
        assertEquals("VALIDATE", outcome.failStep());
        assertTrue(outcome.failReason().contains("source_urls"));
    }

    @Test
    void suggest_duration超出720标FAILED() {
        stubSearchWithMaterial();
        when(chatClient.chatJson(anyString(), anyString())).thenReturn(
                "{\"summary\": \"s\", \"tags\": [], \"open_hours\": null, \"suggest_duration\": 1440}");

        RowEnricher.RowOutcome outcome = enricher.enrich(rawRow());

        assertEquals("FAILED", outcome.row().getEnrichStatus());
        assertEquals("VALIDATE", outcome.failStep());
    }
}
