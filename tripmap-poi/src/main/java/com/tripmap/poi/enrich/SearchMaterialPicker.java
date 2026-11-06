package com.tripmap.poi.enrich;

import com.tripmap.tool.deepseek.DeepSeekSearchClient.SearchResultItem;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 搜索素材挑选器（tripmap-poi功能设计说明书 §4.8）
 *
 * <p>按 {@code rerank_score} 降序取 top 8（官方口径 ≥1.5 为优质），
 * 每个条目取 {@code summary} 优先、{@code content} 兜底的正文并截断，
 * 组装为编号素材（编号与 URL 对应，供 LLM 溯源与人工校对）。</p>
 *
 * <p>落盘约定（§4.1 红线 4）：素材必须持久化到 Excel 列，不允许只存在于内存——
 * {@link Material#snippetsText()} 写入 search_snippets，
 * {@link Material#sourceUrls()} 写入 source_urls。</p>
 */
public final class SearchMaterialPicker {

    /** 取 top N 条素材（§4.8：top 5~8） */
    static final int TOP_N = 8;
    /** 单条素材正文截断长度（字符） */
    static final int SNIPPET_LIMIT = 600;
    /** 素材总长度上限（字符，防 Excel 单元格膨胀） */
    static final int TOTAL_LIMIT = 5000;

    private SearchMaterialPicker() {
    }

    /** 挑选结果：编号素材文本 + 来源 URL 列表（分号分隔前形态） */
    public record Material(String snippetsText, List<String> sourceUrls) {
    }

    /**
     * 从搜索结果中挑选素材。
     *
     * @param results 搜索结果（允许空列表/空正文条目，均跳过）
     * @return 素材（可能为空文本/空 URL 列表：搜索成功但无可用素材，字段输出 null 属正常结果）
     */
    public static Material pick(List<SearchResultItem> results) {
        if (results == null || results.isEmpty()) {
            return new Material("", List.of());
        }
        List<SearchResultItem> top = results.stream()
                .filter(item -> StringUtils.hasText(item.url()))
                .sorted(Comparator.comparingDouble(SearchResultItem::rerankScore).reversed())
                .limit(TOP_N)
                .toList();

        StringBuilder snippets = new StringBuilder();
        java.util.List<String> urls = new java.util.ArrayList<>();
        int index = 0;
        for (SearchResultItem item : top) {
            String text = StringUtils.hasText(item.summary()) ? item.summary() : item.content();
            if (!StringUtils.hasText(text)) {
                continue;
            }
            index++;
            String entry = "[" + index + "] " + nullToEmpty(item.title()) + "\n"
                    + item.url() + "\n" + truncate(text.trim(), SNIPPET_LIMIT);
            if (snippets.length() > 0) {
                entry = "\n---\n" + entry;
            }
            if (snippets.length() + entry.length() > TOTAL_LIMIT) {
                break; // 总上限保护：丢弃后续低分素材
            }
            snippets.append(entry);
            urls.add(item.url());
        }
        return new Material(snippets.toString(), List.copyOf(urls));
    }

    private static String truncate(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit) + "…";
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }

    /** 素材来源 URL 的分号拼接（写入 source_urls 列） */
    public static String joinSourceUrls(List<String> urls) {
        return urls.stream().collect(Collectors.joining(";"));
    }
}
