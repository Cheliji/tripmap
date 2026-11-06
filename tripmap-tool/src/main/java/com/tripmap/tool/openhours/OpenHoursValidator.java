package com.tripmap.tool.openhours;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * open_hours 结构化开放时间校验器（tripmap-poi功能设计说明书 §4.6 三道防线）
 *
 * <p><b>防线 1</b>：功能二输出后立刻校验；<b>防线 2</b>：功能三导入前同一校验器再验；
 * 两处必须共用本实现，防规则漂移。DB 不做 JSON 内容校验（DDL 方言中立约束），
 * 写入管道一处拦截即全局生效。</p>
 *
 * <p>校验口径（三态语义）：</p>
 * <ul>
 *   <li>{@code null}/空白整列 —— 开放时间<b>未知</b>，合法（不阻塞入库）；</li>
 *   <li>某天 = {@code []} —— <b>确定闭馆</b>（空数组即闭馆，比 closed 标志省分支）；</li>
 *   <li>某天 = {@code null} —— 该天<b>未知</b>，合法，不参与时间窗硬约束；</li>
 *   <li>某天 = 时段数组 —— <b>确定开放</b>：时段按时间先后排列、互不重叠、{@code open < close}、{@code HH:mm} 格式。</li>
 * </ul>
 *
 * <p>{@code note}（自由文本例外说明）只校验为字符串或 null，不进时间逻辑；
 * {@code special}（节假日特殊时段）MVP 不校验内容。七键（mon~sun）之外的键一律拒绝（防 LLM 输出漂移）。</p>
 */
public final class OpenHoursValidator {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern HH_MM = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d");

    private static final Map<String, Boolean> DAY_KEYS = Map.of(
            "mon", true, "tue", true, "wed", true, "thu", true,
            "fri", true, "sat", true, "sun", true);

    private OpenHoursValidator() {
    }

    /**
     * 校验 open_hours JSON。
     *
     * @param json open_hours 字符串；null/空白视为"未知"，直接通过
     * @return null 表示通过；否则返回人类可读的错误描述
     */
    public static String validate(String json) {
        if (json == null || json.isBlank()) {
            return null; // 整列空 = 未知，合法（§4.6）
        }
        final JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(json);
        } catch (Exception e) {
            return "不是合法 JSON";
        }
        if (!root.isObject()) {
            return "必须是 JSON 对象";
        }
        Iterator<String> fieldNames = root.fieldNames();
        while (fieldNames.hasNext()) {
            String key = fieldNames.next();
            if (DAY_KEYS.containsKey(key)) {
                String error = validateDay(key, root.get(key));
                if (error != null) {
                    return error;
                }
            } else if ("note".equals(key)) {
                JsonNode note = root.get(key);
                if (note != null && !note.isNull() && !note.isTextual()) {
                    return "note 必须是字符串或 null";
                }
            } else if ("special".equals(key)) {
                JsonNode special = root.get(key);
                if (special != null && !special.isNull() && !special.isArray()) {
                    return "special 必须是数组或 null";
                }
                // special 内容 MVP 不进校验逻辑（§4.6）
            } else {
                return "存在未知键: " + key;
            }
        }
        // 七键必填（§4.6）：某天无可靠依据必须显式置 null，而不是缺键
        for (String day : DAY_KEYS.keySet()) {
            if (!root.has(day)) {
                return "缺少必填键: " + day;
            }
        }
        return null;
    }

    /**
     * 校验某天：null（未知）/ []（闭馆）/ 时段数组（开放）。
     * 时段须按时间先后排列、互不重叠、open &lt; close、HH:mm 格式。
     */
    private static String validateDay(String day, JsonNode node) {
        if (node == null || node.isNull()) {
            return null; // 该天未知，合法
        }
        if (!node.isArray()) {
            return day + " 必须是数组、null（未知）";
        }
        if (node.isEmpty()) {
            return null; // 空数组 = 确定闭馆，合法
        }
        int prevClose = -1;
        for (JsonNode segment : node) {
            if (!segment.isObject()) {
                return day + " 时段必须是对象";
            }
            JsonNode open = segment.get("open");
            JsonNode close = segment.get("close");
            if (open == null || close == null || !open.isTextual() || !close.isTextual()) {
                return day + " 时段缺少 open/close 字符串";
            }
            Integer openMin = parseHhMm(open.asText());
            Integer closeMin = parseHhMm(close.asText());
            if (openMin == null || closeMin == null) {
                return day + " 时段格式必须是 HH:mm: " + open.asText() + "-" + close.asText();
            }
            if (openMin >= closeMin) {
                return day + " 时段 open 必须早于 close: " + open.asText() + "-" + close.asText();
            }
            if (prevClose >= 0 && openMin < prevClose) {
                return day + " 时段必须按时间先后排列且不重叠";
            }
            prevClose = closeMin;
        }
        return null;
    }

    /** 解析 "HH:mm" 为分钟数；非法返回 null */
    private static Integer parseHhMm(String text) {
        if (text == null || !HH_MM.matcher(text).matches()) {
            return null;
        }
        return Integer.parseInt(text.substring(0, 2)) * 60 + Integer.parseInt(text.substring(3, 5));
    }
}
