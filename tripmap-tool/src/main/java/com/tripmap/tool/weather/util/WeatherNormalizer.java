package com.tripmap.tool.weather.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.tripmap.tool.weather.dto.DailyWeather;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 和风逐日预报原始响应 → 归一化 {@link DailyWeather}（任务 2.3，设计说明书 §8.4）。
 *
 * <p>原始 {@code weather/v1/daily} 响应嵌套深、字段带单位对象（temperatureMax.value/unit）、
 * 湿度/降水概率为 0～1 比例。本类只抽取攻略节点（3.7）需要的字段并统一口径：
 * 温度取 °C 数值、降水概率 ×100、日期取 {@code forecastStartTime} 的当地日期部分
 * （必须 {@code localTime=true}，否则 UTC 日期会偏一天，接口文档 §5.2）。</p>
 *
 * <p>天气现象代码（condition.code）为开放集合，不做封闭枚举校验，展示优先用
 * condition.text（接口文档 §7.2）。</p>
 */
public final class WeatherNormalizer {

    private static final Logger log = LoggerFactory.getLogger(WeatherNormalizer.class);

    /** 和风逐日预报响应中的日期格式前缀：yyyy-MM-dd（ISO 8601 前 10 位） */
    private static final int DATE_LEN = 10;

    private WeatherNormalizer() {
    }

    /**
     * 解析逐日预报响应，返回按日期升序排列的归一化列表。
     *
     * @param root {@code GET /weather/v1/daily} 响应体（已解压、已解析为 JSON）
     * @return 逐日天气（仅含非降级日；字段缺失时为 null，消费方判空）
     */
    public static List<DailyWeather> normalizeDaily(JsonNode root) {
        List<DailyWeather> result = new ArrayList<>();
        JsonNode days = root == null ? null : root.path("days");
        if (days == null || !days.isArray()) {
            log.warn("和风逐日预报响应缺少 days 数组: {}", root);
            return result;
        }
        for (JsonNode day : days) {
            result.add(toDaily(day));
        }
        return result;
    }

    /** 单日响应节点 → DailyWeather（比例字段 ×100，温度取 value，日期取当地日期） */
    private static DailyWeather toDaily(JsonNode day) {
        String date = extractDate(day.path("forecastStartTime").asText(null));
        return DailyWeather.builder()
                .date(date)
                .dayCondition(text(day.path("daytime").path("condition").path("text")))
                .nightCondition(text(day.path("nighttime").path("condition").path("text")))
                .tempMaxC(value(day.path("temperatureMax")))
                .tempMinC(value(day.path("temperatureMin")))
                .precipProbabilityPct(probability(day.path("daytime").path("precipitation").path("probability")))
                .precipMm(value(day.path("daytime").path("precipitation").path("amount")))
                .uvIndexMax(integer(day.path("uvIndexMax")))
                .degraded(false)
                .build();
    }

    /** 从 ISO 8601 时间串提取当地日期（yyyy-MM-dd）；非法/缺失返回 null */
    static String extractDate(String isoDateTime) {
        if (isoDateTime == null || isoDateTime.length() < DATE_LEN) {
            return null;
        }
        // 如 2024-08-11T06:00+08:00 / 2024-08-10T22:00Z，日期恒为前 10 位
        return isoDateTime.substring(0, DATE_LEN);
    }

    /** value/unit 结构取数值；缺失返回 null */
    private static Double value(JsonNode node) {
        JsonNode v = node == null ? null : node.path("value");
        return v == null || v.isMissingNode() || v.isNull() ? null : v.asDouble();
    }

    /** 0～1 概率 → 0～100 百分数；缺失返回 null */
    private static Double probability(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.asDouble() * 100.0;
    }

    /** 文本字段；缺失返回 null */
    private static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    /** 整数字段；缺失返回 null */
    private static Integer integer(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asInt();
    }
}
