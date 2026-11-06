package com.tripmap.poi.enrich;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * LLM 输出解析器（功能二流水线第④步，tripmap-poi功能设计说明书 §4.2）
 *
 * <p>只做<b>结构形状</b>解析（字段类型/必填形态），业务校验（受控词表、
 * open_hours Schema、数值区间、交叉校验）由流水线第⑤步统一执行——
 * 解析与校验分层，错误定位清晰。</p>
 *
 * <p>对模型的宽容点：容忍 markdown 代码块包裹；null/缺省统一为"无值"。
 * 对模型的严格点：字段类型错误（如 tags 给字符串、ticket_price 给文本）直接抛
 * {@link IllegalArgumentException}（该行判 FAILED，半成品不落盘，§4.2）。</p>
 */
public final class LlmResponseParser {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private LlmResponseParser() {
    }

    /** LLM 整理结果的结构化载体（可空字段一律 null） */
    public record LlmEnrichResult(String summary, String openHoursJson, List<String> tags,
                                  String ticketPrice, String suggestDuration, String officialAccount) {
    }

    /**
     * 解析 LLM 输出文本。
     *
     * @param text 模型输出（预期为 JSON 对象文本）
     * @return 解析结果
     * @throws IllegalArgumentException 非法 JSON / 字段类型错误
     */
    public static LlmEnrichResult parse(String text) {
        if (!StringUtils.hasText(text)) {
            throw new IllegalArgumentException("LLM 输出为空");
        }
        final JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(stripCodeFence(text));
        } catch (Exception e) {
            throw new IllegalArgumentException("LLM 输出不是合法 JSON: " + abbreviate(text), e);
        }
        if (!root.isObject()) {
            throw new IllegalArgumentException("LLM 输出必须是 JSON 对象");
        }
        return new LlmEnrichResult(
                textField(root, "summary"),
                openHoursField(root),
                tagsField(root),
                nonNegativeNumberField(root, "ticket_price"),
                nonNegativeNumberField(root, "suggest_duration"),
                textField(root, "official_account"));
    }

    /** 容忍 ```json ... ``` 包裹（提示词禁止，但防御性处理，避免因格式判 FAILED） */
    private static String stripCodeFence(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstLineEnd = trimmed.indexOf('\n');
            if (firstLineEnd > 0) {
                trimmed = trimmed.substring(firstLineEnd + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }
        }
        return trimmed.trim();
    }

    /** 字符串字段：null/缺失/空白 → null */
    private static String textField(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new IllegalArgumentException(field + " 必须是字符串或 null");
        }
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }

    /** open_hours：null/缺失 → null；否则原样保留 JSON 文本（Schema 校验由第⑤步执行） */
    private static String openHoursField(JsonNode root) {
        JsonNode node = root.get("open_hours");
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("open_hours 必须是 JSON 对象或 null");
        }
        return node.toString();
    }

    /** tags：null/缺失 → 空列表；必须是字符串数组 */
    private static List<String> tagsField(JsonNode root) {
        JsonNode node = root.get("tags");
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("tags 必须是字符串数组");
        }
        List<String> tags = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw new IllegalArgumentException("tags 元素必须是字符串");
            }
            String tag = item.asText().trim();
            if (!tag.isEmpty()) {
                tags.add(tag);
            }
        }
        return List.copyOf(tags);
    }

    /** 非负数字字段：null/缺失 → null；数字归一化为去尾零文本（45.0 → "45"）；负数/文本报错 */
    private static String nonNegativeNumberField(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isNumber()) {
            throw new IllegalArgumentException(field + " 必须是数字或 null");
        }
        BigDecimal value = node.decimalValue();
        if (value.signum() < 0) {
            throw new IllegalArgumentException(field + " 不允许为负数: " + value);
        }
        return value.stripTrailingZeros().toPlainString();
    }

    private static String abbreviate(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200) + "…";
    }
}
