package com.tripmap.poi.enrich;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LLM 输出解析器单测（功能二流水线第④步）
 *
 * <p>结构形状解析：字段类型错误/非法 JSON 直接抛（该行判 FAILED，半成品不落盘）；
 * null/缺省统一为无值；容忍 markdown 代码块包裹；数字归一化去尾零。</p>
 */
class LlmResponseParserTest {

    @Test
    void 完整输出解析() {
        LlmResponseParser.LlmEnrichResult result = LlmResponseParser.parse("""
                {"summary": "宋代园林，以红鱼池闻名",
                 "open_hours": {"mon": [], "tue": [{"open": "09:00", "close": "17:00"}],
                   "wed": null, "thu": null, "fri": null, "sat": null, "sun": null},
                 "tags": ["园林景观", "拍照打卡"],
                 "ticket_price": 45.0,
                 "suggest_duration": 90,
                 "official_account": "西湖官方"}""");

        assertEquals("宋代园林，以红鱼池闻名", result.summary());
        assertTrue(result.openHoursJson().contains("\"mon\":[]"));
        assertEquals(java.util.List.of("园林景观", "拍照打卡"), result.tags());
        assertEquals("45", result.ticketPrice(), "45.0 应归一化为 45");
        assertEquals("90", result.suggestDuration());
        assertEquals("西湖官方", result.officialAccount());
    }

    @Test
    void 全null与缺省统一为无值() {
        LlmResponseParser.LlmEnrichResult result = LlmResponseParser.parse("{}");
        assertNull(result.summary());
        assertNull(result.openHoursJson());
        assertTrue(result.tags().isEmpty());
        assertNull(result.ticketPrice());
        assertNull(result.suggestDuration());
        assertNull(result.officialAccount());
    }

    @Test
    void markdown代码块包裹可容忍() {
        LlmResponseParser.LlmEnrichResult result = LlmResponseParser.parse(
                "```json\n{\"summary\": \"ok\", \"tags\": []}\n```");
        assertEquals("ok", result.summary());
    }

    @Test
    void 空文本与非法JSON拒绝() {
        assertThrows(IllegalArgumentException.class, () -> LlmResponseParser.parse("  "));
        assertThrows(IllegalArgumentException.class, () -> LlmResponseParser.parse("not-json"));
    }

    @Test
    void 非JSON对象拒绝() {
        assertThrows(IllegalArgumentException.class, () -> LlmResponseParser.parse("[1, 2]"));
    }

    @Test
    void 字段类型错误拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> LlmResponseParser.parse("{\"tags\": \"亲子\"}"));
        assertThrows(IllegalArgumentException.class,
                () -> LlmResponseParser.parse("{\"tags\": [1]}"));
        assertThrows(IllegalArgumentException.class,
                () -> LlmResponseParser.parse("{\"ticket_price\": \"45元\"}"));
        assertThrows(IllegalArgumentException.class,
                () -> LlmResponseParser.parse("{\"suggest_duration\": \"两小时\"}"));
        assertThrows(IllegalArgumentException.class,
                () -> LlmResponseParser.parse("{\"open_hours\": \"09:00-17:00\"}"));
        assertThrows(IllegalArgumentException.class,
                () -> LlmResponseParser.parse("{\"summary\": 123}"));
    }

    @Test
    void 负数拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> LlmResponseParser.parse("{\"ticket_price\": -5}"));
    }
}
