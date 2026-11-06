package com.tripmap.poi.enrich;

import com.tripmap.tool.deepseek.DeepSeekSearchClient.SearchResultItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 搜索素材挑选器单测（tripmap-poi功能设计说明书 §4.8）
 *
 * <p>按 rerank_score 降序取 top 8、summary 优先 content 兜底、
 * 单条与总长截断、空条目跳过、来源 URL 顺序跟随素材编号。</p>
 */
class SearchMaterialPickerTest {

    private SearchResultItem item(String url, double score, String summary, String content) {
        return new SearchResultItem("标题-" + url, url, summary, content, score);
    }

    @Test
    void 按rerank_score降序取top8() {
        List<SearchResultItem> results = new java.util.ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            results.add(item("https://a.com/" + i, i, "素材" + i, null));
        }
        SearchMaterialPicker.Material material = SearchMaterialPicker.pick(results);

        assertEquals(8, material.sourceUrls().size(), "应取 top 8");
        assertEquals("https://a.com/10", material.sourceUrls().get(0), "最高分在前");
        assertEquals("https://a.com/3", material.sourceUrls().get(7));
        assertTrue(material.snippetsText().startsWith("[1] 标题-https://a.com/10"));
    }

    @Test
    void summary优先content兜底() {
        SearchMaterialPicker.Material material = SearchMaterialPicker.pick(List.of(
                item("https://a.com/1", 2.0, "摘要内容", "正文内容"),
                item("https://a.com/2", 1.0, null, "只有正文"),
                item("https://a.com/3", 0.5, "", null)));
        assertEquals(2, material.sourceUrls().size(), "无 summary 且无 content 的条目跳过");
        assertTrue(material.snippetsText().contains("摘要内容"));
        assertTrue(material.snippetsText().contains("只有正文"));
        assertTrue(!material.snippetsText().contains("https://a.com/3"));
    }

    @Test
    void 空url条目跳过() {
        SearchMaterialPicker.Material material = SearchMaterialPicker.pick(List.of(
                item("", 2.0, "无URL", null),
                item("https://a.com/1", 1.0, "有URL", null)));
        assertEquals(List.of("https://a.com/1"), material.sourceUrls());
    }

    @Test
    void 单条与总长截断生效() {
        String longText = "长".repeat(2000);
        List<SearchResultItem> results = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            results.add(item("https://a.com/" + i, 10.0 - i, longText, null));
        }
        SearchMaterialPicker.Material material = SearchMaterialPicker.pick(results);
        assertTrue(material.snippetsText().length() <= SearchMaterialPicker.TOTAL_LIMIT + 200,
                "总长应受 TOTAL_LIMIT 约束: " + material.snippetsText().length());
        assertTrue(material.snippetsText().contains("…"), "单条应截断并带省略号");
    }

    @Test
    void 空输入返回空素材() {
        SearchMaterialPicker.Material material = SearchMaterialPicker.pick(List.of());
        assertEquals("", material.snippetsText());
        assertTrue(material.sourceUrls().isEmpty());
        SearchMaterialPicker.Material nullMaterial = SearchMaterialPicker.pick(null);
        assertEquals("", nullMaterial.snippetsText());
    }

    @Test
    void 来源URL分号拼接() {
        assertEquals("a;b", SearchMaterialPicker.joinSourceUrls(List.of("a", "b")));
        assertEquals("", SearchMaterialPicker.joinSourceUrls(List.of()));
    }
}
