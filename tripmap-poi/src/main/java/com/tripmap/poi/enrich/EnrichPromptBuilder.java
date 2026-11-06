package com.tripmap.poi.enrich;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tripmap.common.constant.PoiTagVocab;
import com.tripmap.poi.excel.RawPoiExcelRow;
import org.springframework.util.StringUtils;

/**
 * 功能二 LLM 提示词构建（tripmap-poi功能设计说明书 §4.7/§4.8）
 *
 * <p>系统提示词明写全部红线：LLM 是"整理器"不是"事实源"（AGENTS.md 核心原则 2），
 * 只能提取/归纳/结构化给定素材，素材无依据一律 null、禁止无中生有；
 * open_hours 以高德 opentime2/open_time 为底稿、搜索素材仅核实/补充/纠正；
 * tags 只能多选受控词表；summary 客观化风格；official_account 禁止猜测拼合。</p>
 */
public final class EnrichPromptBuilder {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private EnrichPromptBuilder() {
    }

    /** 系统提示词（全部整理规则与受控词表，§4.8） */
    public static String systemPrompt() {
        return """
                你是旅游 POI 信息整理器，把给定的搜索素材整理为结构化 JSON。
                你是"整理器"不是"事实源"，必须遵守以下规则：

                1. 所有事实只能来自输入素材和高德字段，禁止补充素材外信息、禁止凭空猜测；
                   素材没有依据的字段一律输出 null。素材与高德字段冲突时以素材为准。
                2. open_hours：优先以高德 opentime2（含闭馆日全式）为底稿解析，
                   open_time（简式）辅助；搜索素材仅用于核实、补充、纠正。
                   输出为 JSON 对象，键为 mon~sun 七个英文星期缩写（七键必须齐全）：
                   - 确定开放：值为时段数组 [{"open":"HH:mm","close":"HH:mm"}]，
                     时段按时间先后排列、互不重叠、open 早于 close；
                   - 确定闭馆：值为空数组 []；
                   - 该天无可靠依据：值为 null（未知不等于闭馆）；
                   - 整列都没有可靠依据时，open_hours 整体输出 null；
                   - 例外说明（法定节假日顺延等）写入 note 字符串字段。
                3. tags：只能从以下受控词表中多选（不要输出词表之外的标签；
                   没有依据就少选，允许为空数组）：
                """ + PoiTagVocab.groupedPromptText() + """

                4. summary：用一句话客观概述景点，必须包含"看什么/玩什么/游览特点"，
                   禁止营销形容词（如"绝美""打卡圣地"），禁止素材外信息。
                   示例："宋代园林，以红鱼池、牡丹园闻名，环湖平路游览约 1.5 小时"。
                5. ticket_price：门票价格，数字（单位元），0 表示免费（须有素材依据），无依据输出 null。
                6. suggest_duration：建议游玩时长，整数分钟（如 90），无依据输出 null。
                7. official_account：景点官方公众号名称；素材明确给出才输出，否则一律 null，禁止猜测或拼合。
                8. 只输出 JSON 对象本身，不要用 markdown 代码块包裹。
                """;
    }

    /**
     * 用户消息：高德字段 + 编号搜索素材（编号与 URL 一一对应，便于溯源）。
     *
     * @param raw      raw 行（高德字段底稿）
     * @param material 挑选后的搜索素材（可能为空）
     */
    public static String userMessage(RawPoiExcelRow raw, SearchMaterialPicker.Material material) {
        ObjectNode poi = OBJECT_MAPPER.createObjectNode();
        poi.put("name", raw.getName());
        poi.put("type", raw.getType());
        poi.put("typecode", raw.getTypecode());
        poi.put("level", raw.getLevel());
        poi.put("rating", raw.getRating());
        poi.put("keytag", raw.getKeytag());
        poi.put("address", raw.getAddress());
        poi.put("adname", raw.getAdname());
        poi.put("open_time", raw.getOpenTime());
        poi.put("opentime2", raw.getOpentime2());

        StringBuilder sb = new StringBuilder();
        sb.append("高德字段底稿：\n").append(poi.toPrettyString()).append("\n\n搜索素材：\n");
        if (StringUtils.hasText(material.snippetsText())) {
            sb.append(material.snippetsText());
        } else {
            sb.append("（无可用搜索结果，仅能依据高德字段整理，无依据的字段一律 null）");
        }
        return sb.toString();
    }
}
