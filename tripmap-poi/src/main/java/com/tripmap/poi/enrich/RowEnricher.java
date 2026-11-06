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
import java.time.LocalDate;
import java.util.ArrayList;
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
 *
 * <p>落盘口径（v1.4）：①②映射结果与已取得的搜索素材在任何失败分支都保留——
 * 映射是纯代码产物，素材属中间状态（§4.1 红线 4），FAILED 行同样落盘供人工校对。
 * 搜索无素材时 open_hours 允许纯依高德 opentime2 底稿（source_urls 可为空，§4.10 v1.4）。
 * SUCCESS 行附 remark：固定模板列出本行 AI 生成字段，供运行时 LLM 时效性说明依据。</p>
 */
public class RowEnricher {

    private static final Logger log = LoggerFactory.getLogger(RowEnricher.class);

    /** 搜索查询后缀（§4.8 组合查询口径） */
    private static final String QUERY_SUFFIX = " 开放时间 门票 游玩攻略";
    /** 官方 query 长度上限 128 字符（§4.8） */
    private static final int QUERY_MAX_LEN = 128;
    /** 联网搜索只取近一年素材（官方搜索语法 after: 操作符内嵌 query，项目方确认口径） */
    private static final int SEARCH_WITHIN_YEARS = 1;
    /** suggest_duration 合法区间（分钟，§5 导入校验口径前置到增强期，防脏数据落盘） */
    private static final BigDecimal DURATION_MIN = BigDecimal.ZERO;
    private static final BigDecimal DURATION_MAX = new BigDecimal(720);
    /** remark 固定模板前缀（v1.4）：只声明哪些字段由 AI 生成，不记录来源与 URL */
    private static final String REMARK_PREFIX = "以下字段由AI联网搜索整理，可能存在时效差异：";

    private final DeepSeekSearchClient searchClient;
    private final DeepSeekChatClient chatClient;
    private final DistrictAdcodeMapping districtMapping;
    private final CategoryMapping categoryMapping;
    /** 素材新鲜度下限：after: 操作符取值（当天往前推一年） */
    private final LocalDate searchAfterDate;

    public RowEnricher(DeepSeekSearchClient searchClient, DeepSeekChatClient chatClient,
                       DistrictAdcodeMapping districtMapping, CategoryMapping categoryMapping) {
        this(searchClient, chatClient, districtMapping, categoryMapping,
                LocalDate.now().minusYears(SEARCH_WITHIN_YEARS));
    }

    /** 测试专用：注入固定 after 日期，保证查询词可断言 */
    RowEnricher(DeepSeekSearchClient searchClient, DeepSeekChatClient chatClient,
                DistrictAdcodeMapping districtMapping, CategoryMapping categoryMapping,
                LocalDate searchAfterDate) {
        this.searchClient = searchClient;
        this.chatClient = chatClient;
        this.districtMapping = districtMapping;
        this.categoryMapping = categoryMapping;
        this.searchAfterDate = searchAfterDate;
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
        // 基行：19 列原样 + ①②映射（映射结果在任何失败分支都保留，v1.4）
        EnrichedPoiExcelRow row = EnrichedPoiExcelRow.from(raw);
        districtMapping.lookup(raw.getAdname()).ifPresentOrElse(
                row::setDistrictAdcode,
                () -> log.warn("[{}] adname 未命中 district_adcode 映射: adname={}", raw.getAmapId(), raw.getAdname()));
        categoryMapping.map(raw.getTypecode()).ifPresentOrElse(
                c -> row.setCategory(c.getLabel()),
                () -> log.warn("[{}] typecode 未命中 category 映射: typecode={}", raw.getAmapId(), raw.getTypecode()));

        // ③ 联网搜索 1 次
        List<SearchResultItem> results;
        try {
            results = searchClient.search(buildQuery(raw));
        } catch (RuntimeException e) {
            return failed(row, "SEARCH", rootMessage(e));
        }
        SearchMaterialPicker.Material material = SearchMaterialPicker.pick(results);
        // 素材属中间状态，FAILED 行同样落盘（§4.1 红线 4）
        row.setSearchSnippets(StringUtils.hasText(material.snippetsText()) ? material.snippetsText() : null);
        row.setSourceUrls(StringUtils.hasText(SearchMaterialPicker.joinSourceUrls(material.sourceUrls()))
                ? SearchMaterialPicker.joinSourceUrls(material.sourceUrls()) : null);

        // ④ LLM 结构化整理 1 次
        String llmJson;
        try {
            llmJson = chatClient.chatJson(EnrichPromptBuilder.systemPrompt(),
                    EnrichPromptBuilder.userMessage(raw, material));
        } catch (RuntimeException e) {
            return failed(row, "LLM", rootMessage(e));
        }
        LlmResponseParser.LlmEnrichResult llmResult;
        try {
            llmResult = LlmResponseParser.parse(llmJson);
        } catch (IllegalArgumentException e) {
            return failed(row, "LLM", "LLM 输出解析失败: " + e.getMessage());
        }

        // ⑤ 输出校验（半成品不落盘：校验全部通过后才写入 LLM 字段）
        String error = validate(llmResult);
        if (error != null) {
            return failed(row, "VALIDATE", error);
        }

        row.setSummary(llmResult.summary());
        row.setOpenHours(llmResult.openHoursJson());
        row.setTags(llmResult.tags().isEmpty() ? null : String.join(";", llmResult.tags()));
        row.setTicketPrice(llmResult.ticketPrice());
        row.setSuggestDuration(llmResult.suggestDuration());
        row.setOfficialAccount(llmResult.officialAccount());
        row.setRemark(buildRemark(llmResult));
        row.setEnrichStatus("SUCCESS");
        return new RowOutcome(row, null, null);
    }

    /**
     * 第⑤步输出校验（§4.2）：
     * open_hours 过 JSON Schema 校验器（防线 1，与功能三同一实现）；
     * tags 逐条在受控词表内；suggest_duration 在 (0, 720]。
     *
     * <p>来源约束（v1.4 放宽）：搜索无素材时 open_hours 允许纯依高德 opentime2 底稿，
     * source_urls 可为空；搜索有素材时素材 URL 已落盘，LLM 仅被允许从素材取证（Prompt 约束）。
     *
     * @return null 表示通过，否则返回错误描述
     */
    private String validate(LlmResponseParser.LlmEnrichResult result) {
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
        return null;
    }

    /** remark 组装（v1.4）：固定模板列出本行 AI 生成的非空字段；无 AI 字段返回 null */
    private String buildRemark(LlmResponseParser.LlmEnrichResult result) {
        List<String> fields = new ArrayList<>();
        if (StringUtils.hasText(result.summary())) {
            fields.add("景点概述");
        }
        if (StringUtils.hasText(result.openHoursJson())) {
            fields.add("开放时间");
        }
        if (!result.tags().isEmpty()) {
            fields.add("标签");
        }
        if (StringUtils.hasText(result.ticketPrice())) {
            fields.add("门票价格");
        }
        if (StringUtils.hasText(result.suggestDuration())) {
            fields.add("建议游玩时长");
        }
        if (StringUtils.hasText(result.officialAccount())) {
            fields.add("官方公众号");
        }
        return fields.isEmpty() ? null : REMARK_PREFIX + String.join("、", fields);
    }

    /**
     * 组合查询："{name} {cityname} 开放时间 门票 攻略 after:{一年前的今天}"（§4.8）。
     * after: 为官方搜索语法操作符，将结果限制在近一年内发布；超 128 字符截断主体、
     * 保留完整 after: 操作符（截断操作符会导致过滤静默失效）。
     */
    private String buildQuery(RawPoiExcelRow raw) {
        String core = nullToEmpty(raw.getName()) + " " + nullToEmpty(raw.getCityname()) + QUERY_SUFFIX;
        String afterToken = " after:" + searchAfterDate;
        int budget = QUERY_MAX_LEN - afterToken.length();
        if (core.length() > budget) {
            core = core.substring(0, budget);
        }
        return (core + afterToken).trim();
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
