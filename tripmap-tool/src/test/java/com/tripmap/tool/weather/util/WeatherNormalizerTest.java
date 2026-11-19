package com.tripmap.tool.weather.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripmap.tool.weather.dto.DailyWeather;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 天气归一化器单测（任务 2.3）：原始响应 → 归一化 DailyWeather。
 *
 * <p>按 AGENTS.md 定规：每个用例打印场景、输入与输出，console 可直接人工 review。</p>
 */
class WeatherNormalizerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonNode json(String s) throws Exception {
        return MAPPER.readTree(s);
    }

    @Test
    void 常规两日预报归一化与口径转换() throws Exception {
        JsonNode root = json("""
                {
                  "days": [
                    {
                      "forecastStartTime": "2026-11-19T06:00+08:00",
                      "temperatureMax": { "value": 23.4, "unit": "°C" },
                      "temperatureMin": { "value": 14.1, "unit": "°C" },
                      "uvIndexMax": 4,
                      "daytime": {
                        "condition": { "text": "多云", "code": "101" },
                        "precipitation": { "amount": { "value": 0.0, "unit": "mm" }, "probability": 0.10, "type": "rain" }
                      },
                      "nighttime": { "condition": { "text": "晴", "code": "100" } }
                    },
                    {
                      "forecastStartTime": "2026-11-20T06:00+08:00",
                      "temperatureMax": { "value": 18.7, "unit": "°C" },
                      "temperatureMin": { "value": 9.3, "unit": "°C" },
                      "uvIndexMax": 2,
                      "daytime": {
                        "condition": { "text": "小雨", "code": "305" },
                        "precipitation": { "amount": { "value": 2.5, "unit": "mm" }, "probability": 0.85, "type": "rain" }
                      },
                      "nighttime": { "condition": { "text": "阴", "code": "104" } }
                    }
                  ]
                }
                """);

        List<DailyWeather> result = WeatherNormalizer.normalizeDaily(root);

        System.out.println("\n========== 场景：两日预报归一化 ==========");
        for (DailyWeather d : result) {
            System.out.printf("  日期=%s 白天=%s 夜间=%s 最高=%.1f°C 最低=%.1f°C 降水概率=%.0f%% 降水=%.1fmm UV=%d%n",
                    d.getDate(), d.getDayCondition(), d.getNightCondition(),
                    d.getTempMaxC(), d.getTempMinC(),
                    d.getPrecipProbabilityPct(), d.getPrecipMm(), d.getUvIndexMax());
        }

        assertEquals(2, result.size());
        DailyWeather first = result.get(0);
        assertEquals("2026-11-19", first.getDate());
        assertEquals("多云", first.getDayCondition());
        assertEquals("晴", first.getNightCondition());
        assertEquals(23.4, first.getTempMaxC());
        assertEquals(14.1, first.getTempMinC());
        assertEquals(10.0, first.getPrecipProbabilityPct(), "0.10 概率应 ×100 = 10%");
        assertEquals(0.0, first.getPrecipMm());
        assertEquals(4, first.getUvIndexMax());
        assertEquals(false, first.isDegraded());

        DailyWeather second = result.get(1);
        assertEquals("2026-11-20", second.getDate());
        assertEquals(85.0, second.getPrecipProbabilityPct(), "0.85 概率应 ×100 = 85%");
        assertEquals(2.5, second.getPrecipMm());
    }

    @Test
    void 缺失days数组返回空列表() throws Exception {
        JsonNode root = json("{\"metadata\": {}}");
        List<DailyWeather> result = WeatherNormalizer.normalizeDaily(root);
        System.out.println("\n========== 场景：缺少 days 数组 → 空列表 ==========");
        System.out.printf("  返回条数=%d%n", result.size());
        assertTrue(result.isEmpty());
    }

    @Test
    void 字段缺失时为null不抛异常() throws Exception {
        JsonNode root = json("""
                {
                  "days": [
                    {
                      "forecastStartTime": "2026-11-19T06:00+08:00"
                    }
                  ]
                }
                """);
        List<DailyWeather> result = WeatherNormalizer.normalizeDaily(root);
        System.out.println("\n========== 场景：字段缺失 → null（消费方判空） ==========");
        System.out.printf("  date=%s 白天=%s 最高=%s 降水概率=%s%n",
                result.get(0).getDate(), result.get(0).getDayCondition(),
                result.get(0).getTempMaxC(), result.get(0).getPrecipProbabilityPct());

        assertEquals(1, result.size());
        assertEquals("2026-11-19", result.get(0).getDate());
        assertNull(result.get(0).getDayCondition());
        assertNull(result.get(0).getTempMaxC());
        assertNull(result.get(0).getPrecipProbabilityPct());
    }

    @Test
    void 日期提取兼容当地偏移与UTC两种格式() {
        System.out.println("\n========== 场景：日期提取（localTime=true 当地偏移 / UTC） ==========");
        String local = "2026-11-19T06:00+08:00";
        String utc = "2026-11-18T22:00Z";
        String localDate = WeatherNormalizer.extractDate(local);
        String utcDate = WeatherNormalizer.extractDate(utc);
        System.out.printf("  %s → %s；%s → %s（UTC 需配 localTime=true 才对应当地时间）%n",
                local, localDate, utc, utcDate);
        assertEquals("2026-11-19", localDate);
        assertEquals("2026-11-18", utcDate, "extractDate 只取字符串前 10 位，不做时区换算");
    }
}
