package com.tripmap.poi.enrich;

import com.tripmap.common.constant.PoiTagVocab;
import com.tripmap.poi.excel.EnrichedPoiExcelRow;
import com.tripmap.poi.excel.RawPoiExcelRow;
import com.tripmap.tool.deepseek.DeepSeekChatClient;
import com.tripmap.tool.deepseek.DeepSeekSearchClient;
import com.tripmap.tool.deepseek.DeepSeekSearchClient.SearchResultItem;
import com.tripmap.tool.openhours.OpenHoursValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 单条 POI 五步增强流水线（tripmap-poi功能设计说明书 §4.2）：
 *
 * <pre>
 * ① district_adcode：adname 查静态映射（纯代码，零 API）
 * ② category：typecode 规则映射（纯代码，零 API，不经 LLM）
 * ③ 联网搜索 1 次 → search_snippets / source_urls 落盘
 * ④ LLM 结构化整理 1 次 → summary / open_hours / tags / ticket_price / suggest_duration / official_account
 * ⑤ 输出校验：open_hours 过 JSON Schema 校验器；tags 校验在受控词表内
 * </pre>
 *
 * <p>口径（§4.2）：③④ 失败 → 该行 enrich_status=FAILED 并记录失败步骤与原因，继续下一行；
 * ⑤ 失败（含半成品 JSON）→ FAILED，<b>半成品不落盘</b>；
 * ①② 未命中仅 warn（字段留空），行仍可判 SUCCESS。每条 POI = 1 次搜索 + 1 次 LLM，
 * 禁止拆成多次 LLM 调用。</p>
 */
public class RowEnricher {

    private static final Logger log = LoggerFactory.getLogger(RowEnricher.class);

    /** 搜索查询后缀（§4.8 组合查询口径） */
    private static final String QUERY_SUFFIX = " 开放时间 门票 游玩攻略";
    /** 官方 query 长度上限 128 字符（§4.8） */
    private static final int QUERY_MAX_LEN = 128;
    /** suggest_duration 合法区间（分钟，§5 导入校验口径前置到增强期，防脏数据落盘） */
    private static final BigDecimal DURATION_MIN = BigDecimal.ZERO;
    private static final BigDecimal DURATION_MAX = new BigDecimal(720);

    private final DeepSeekSearchClient searchClient;
    private final DeepSeekChatClient chatClient;
    private final DistrictAdcodeMapping districtMapping;
    private final CategoryMapping categoryMapping;

    public RowEnricher(DeepSeekSearchClient searchClient, DeepSeekChatClient chatClient,
                       DistrictAdcodeMapping districtMapping, CategoryMapping categoryMapping) {
        this.searchClient = searchClient;
        this.chatClient = chatClient;
        this.districtMapping = districtMapping;
        this.categoryMapping = categoryMapping;
    }

    /** 单条处理结果：行（enrichStatus=SUCCESS/FAILED）+ 失败步骤/原因（成功为 null） */
    public record RowOutcome(EnrichedPoiExcelRow row, String failStep, String failReason) {

        public boolean success() {
            return failStep == null;
        }
    }

    /**
     * 处理一条 raw 行（流水线全程零高德 API 调用，§4.2）。
     *
     * @param raw 输入行（19 列原样进入输出行）
     * @return 处理结果；任何一步失败返回 enrichStatus=FAILED 的行，不抛异常
     */
    public RowOutcome enrich(RawPoiExcelRow raw) {
        // ① district_adcode：未命中仅 warn，行仍可 SUCCESS（§4.2）
        Optional<String> adcode = districtMapping.lookup(raw.getAdname());
        if (adcode.isEmpty()) {
            log.warn("[{}] adname 未命中 district_adcode 映射: adname={}", raw.getAmapId(), raw.getAdname());
        }

        // ② category：未命中仅 warn，行仍可 SUCCESS（§4.2）
        Optional<com.tripmap.common.constant.PoiCategory> category = categoryMapping.map(raw.getTypecode());
        if (category.isEmpty()) {
            log.warn("[{}] typecode 未命中 category 映射: typecode={}", raw.getAmapId(), raw.getTypecode());
        }

        // ③ 联网搜索 1 次
        List<SearchResultItem> results;
        try {
            results = searchClient.search(buildQuery(raw));
        } catch (RuntimeException e) {
            return failed(EnrichedPoiExcelRow.from(raw), "SEARCH", rootMessage(e));
        }
        SearchMaterialPicker.Material material = SearchMaterialPicker.pick(results);
        String sourceUrls = SearchMaterialPicker.joinSourceUrls(material.sourceUrls());

        // ④ LLM 结构化整理 1 次
        String llmJson;
        try {
            llmJson = chatClient.chatJson(EnrichPromptBuilder.systemPrompt(),
                    EnrichPromptBuilder.userMessage(raw, material));
        } catch (RuntimeException e) {
            return failed(EnrichedPoiExcelRow.from(raw), "LLM", rootMessage(e));
        }
        LlmResponseParser.LlmEnrichResult llmResult;
        try {
            llmResult = LlmResponseParser.parse(llmJson);
        } catch (IllegalArgumentException e) {
            return failed(EnrichedPoiExcelRow.from(raw), "LLM", "LLM 输出解析失败: " + e.getMessage());
        }

        // ⑤ 输出校验（半成品不落盘：校验全部通过后才组装 SUCCESS 行）
        String error = validate(llmResult, sourceUrls);
        if (error != null) {
            return failed(EnrichedPoiExcelRow.from(raw), "VALIDATE", error);
        }

        EnrichedPoiExcelRow row = EnrichedPoiExcelRow.from(raw);
        adcode.ifPresent(row::setDistrictAdcode);
        category.ifPresent(c -> row.setCategory(c.getLabel()));
        row.setSummary(llmResult.summary());
        row.setOpenHours(llmResult.openHoursJson());
        row.setTags(String.join(";", llmResult.tags()));
        row.setTicketPrice(llmResult.ticketPrice());
        row.setSuggestDuration(llmResult.suggestDuration());
        row.setOfficialAccount(llmResult.officialAccount());
        row.setSearchSnippets(material.snippetsText());
        row.setSourceUrls(sourceUrls);
        row.setEnrichStatus("SUCCESS");
        return new RowOutcome(row, null, null);
    }

    /**
     * 第⑤步输出校验（§4.2）：
     * open_hours 过 JSON Schema 校验器（防线 1，与功能三同一实现）；
     * tags 逐条在受控词表内；suggest_duration 在 (0, 720]；
     * 交叉校验：open_hours 非空 ⇒ source_urls 非空（§4.10 硬性约定）。
     *
     * @return null 表示通过，否则返回错误描述
     */
    private String validate(LlmResponseParser.LlmEnrichResult result, String sourceUrls) {
        String openHoursError = OpenHoursValidator.validate(result.openHoursJson());
        if (openHoursError != null) {
            return "open_hours 校验失败: " + openHoursError;
        }
        for (String tag : result.tags()) {
            if (!PoiTagVocab.isValid(tag)) {
                return "tags 超出受控词表: " + tag;
            }
        }
        if (result.suggestDuration() != null) {
            BigDecimal duration = new BigDecimal(result.suggestDuration());
            if (duration.compareTo(DURATION_MIN) <= 0 || duration.compareTo(DURATION_MAX) > 0) {
                return "suggest_duration 超出合法区间 (0, 720]: " + result.suggestDuration();
            }
        }
        if (StringUtils.hasText(result.openHoursJson()) && !StringUtils.hasText(sourceUrls)) {
            return "open_hours 非空但 source_urls 为空（违反 §4.10 来源约束）";
        }
        return null;
    }

    /** 组合查询："{name} {cityname} 开放时间 门票 游玩攻略"，超 128 字符截断（§4.8） */
    private String buildQuery(RawPoiExcelRow raw) {
        String query = nullToEmpty(raw.getName()) + " " + nullToEmpty(raw.getCityname()) + QUERY_SUFFIX;
        if (query.length() > QUERY_MAX_LEN) {
            query = query.substring(0, QUERY_MAX_LEN);
        }
        return query.trim();
    }

    private RowOutcome failed(EnrichedPoiExcelRow row, String step, String reason) {
        row.setEnrichStatus("FAILED");
        log.warn("[{}] 增强失败 step={} reason={}", row.getAmapId(), step, reason);
        return new RowOutcome(row, step, reason);
    }

    /** 取异常根因消息（ExternalApiException 等的可读描述） */
    private String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return e.getMessage() != null ? e.getMessage() : root.getClass().getSimpleName();
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text.trim();
    }
}
