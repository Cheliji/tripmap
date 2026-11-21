package com.tripmap.tool.search.util;

import com.tripmap.tool.search.util.UncitedFactSentenceFilter.RemovedSentence;
import com.tripmap.tool.search.util.UncitedFactSentenceFilter.Result;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "无来源事实句删除"后处理器单测（TASKS 2.7.2）。
 *
 * <p>覆盖契约全部分支：事实句无引用删除、带合法引用保留、引用越界删除、
 * 非事实过渡句保留、Markdown 标题豁免、统计与删除原因可追溯。
 * 日志输出删除报告（AGENTS.md 测试定规）。</p>
 */
class UncitedFactSentenceFilterTest {

    /** 模拟一次搜索带来 3 条来源 */
    private static final int SOURCE_COUNT = 3;

    @Test
    void 事实句无引用被删除() {
        String text = "灵隐寺需要提前预约。\n建议穿舒适的鞋子。";
        System.out.println("[场景] \"灵隐寺需要提前预约。\"含事实关键词\"预约\"且无 [n] → 应删除；"
                + "\"建议穿舒适的鞋子。\"非事实句 → 保留");
        printInput(text);

        Result r = UncitedFactSentenceFilter.filter(text, SOURCE_COUNT);
        printResult(r);

        assertTrue(r.hasRemoval());
        assertEquals(1, r.removedSentenceCount());
        assertEquals("事实句缺少来源引用", r.removedSentences().get(0).reason());
        assertTrue(r.removedSentences().get(0).sentence().contains("灵隐寺"));
        assertEquals("建议穿舒适的鞋子。", r.filteredText().trim());
        assertEquals(1, r.keptSentenceCount());
    }

    @Test
    void 事实句带合法引用保留() {
        String text = "灵隐寺需要提前预约[1]。\n西湖景区免费开放[2]。";
        System.out.println("[场景] 两条事实句分别挂 [1]/[2]（均 ≤ 来源总数 3）→ 全部保留");
        printInput(text);

        Result r = UncitedFactSentenceFilter.filter(text, SOURCE_COUNT);
        printResult(r);

        assertFalse(r.hasRemoval());
        assertEquals(0, r.removedSentenceCount());
        assertEquals(2, r.keptSentenceCount());
        assertEquals(text, r.filteredText());
    }

    @Test
    void 一句挂多个引用任一越界即删除() {
        String text = "雷峰塔门票40元[2][9]，建议线上购买。";
        System.out.println("[场景] 引用 [2] 合法但 [9] 越界（来源总数 3）→ 整句删除，防幻觉来源");
        printInput(text);

        Result r = UncitedFactSentenceFilter.filter(text, SOURCE_COUNT);
        printResult(r);

        assertEquals(1, r.removedSentenceCount());
        assertTrue(r.removedSentences().get(0).reason().contains("引用序号越界"));
        assertTrue(r.removedSentences().get(0).reason().contains("[9]"));
        assertTrue(r.filteredText().isBlank());
    }

    @Test
    void 含数字但事实完整引用的句子保留() {
        String text = "开放时间为7:30至17:30[3]。";
        System.out.println("[场景] 含数字（7:30/17:30）但事实句挂合法引用 [3] → 保留");
        printInput(text);

        Result r = UncitedFactSentenceFilter.filter(text, SOURCE_COUNT);
        printResult(r);

        assertFalse(r.hasRemoval());
        assertEquals(text, r.filteredText());
    }

    @Test
    void 含数字但无引用的事实句删除() {
        String text = "开放时间为7:30至17:30。";
        System.out.println("[场景] 含数字但无任何引用 → 删除（开放时间类事实错误成本高，§14）");
        printInput(text);

        Result r = UncitedFactSentenceFilter.filter(text, SOURCE_COUNT);
        printResult(r);

        assertEquals(1, r.removedSentenceCount());
        assertEquals("事实句缺少来源引用", r.removedSentences().get(0).reason());
    }

    @Test
    void markdown标题含数字也豁免() {
        String text = "## 第2天 西湖环湖\n这一天行程轻松。";
        System.out.println("[场景] Markdown 标题行含数字\"2\"但以 # 开头 → 视为标题保留；正文非事实句保留");
        printInput(text);

        Result r = UncitedFactSentenceFilter.filter(text, SOURCE_COUNT);
        printResult(r);

        assertFalse(r.hasRemoval());
        assertEquals(text, r.filteredText());
        assertEquals(2, r.keptSentenceCount());
    }

    @Test
    void 非事实过渡句与问候保留() {
        String text = "祝你旅途愉快！\n接下来我们看看第二天的安排。";
        System.out.println("[场景] 感叹句与过渡句不触发事实模式（无数字/关键词）→ 保留");
        printInput(text);

        Result r = UncitedFactSentenceFilter.filter(text, SOURCE_COUNT);
        printResult(r);

        assertFalse(r.hasRemoval());
        assertEquals(text, r.filteredText());
    }

    @Test
    void 价格类事实必须引用() {
        String text = "门票价格为45元。\n票价不贵，值得一看。";
        System.out.println("[场景] \"45元\"触发事实模式且无引用 → 删除；\"票价不贵\"无数字 → 保留");
        printInput(text);

        Result r = UncitedFactSentenceFilter.filter(text, SOURCE_COUNT);
        printResult(r);

        assertEquals(1, r.removedSentenceCount());
        assertEquals("票价不贵，值得一看。", r.filteredText().trim());
    }

    @Test
    void 空文本与null安全返回() {
        System.out.println("[场景] null / 空文本 / 空白文本 → 安全返回空结果，不抛异常");
        Result nullResult = UncitedFactSentenceFilter.filter(null, SOURCE_COUNT);
        Result blankResult = UncitedFactSentenceFilter.filter("  \n ", SOURCE_COUNT);
        printResult(nullResult);
        printResult(blankResult);
        assertEquals("", nullResult.filteredText());
        assertFalse(nullResult.hasRemoval());
        assertEquals("", blankResult.filteredText());
    }

    @Test
    void 删除原因可追溯且不静默丢弃() {
        String text = "甲事实100元无引用。\n乙事实引用越界[7]。\n丙事实合法[1]。";
        System.out.println("[场景] 混合三句 → 删 2 留 1，removedSentences 按原文顺序带原因");
        printInput(text);

        Result r = UncitedFactSentenceFilter.filter(text, SOURCE_COUNT);
        printResult(r);

        assertEquals(2, r.removedSentenceCount());
        assertEquals(1, r.keptSentenceCount());
        assertEquals("事实句缺少来源引用", r.removedSentences().get(0).reason());
        assertTrue(r.removedSentences().get(1).reason().contains("引用序号越界"));
        assertEquals("丙事实合法[1]。", r.filteredText().trim());
        // 被删句子原文可追溯（评测 M5 与人工 review 核对用）
        assertTrue(r.removedSentences().stream().map(RemovedSentence::sentence)
                .anyMatch(s -> s.contains("甲事实100元无引用")));
    }

    @Test
    void 多行结构原样保留() {
        String text = "第一段。\n\n第二段事实100元[2]。\n第三段。";
        System.out.println("[场景] 空行与多行结构 → 过滤后换行结构不塌");
        printInput(text);

        Result r = UncitedFactSentenceFilter.filter(text, SOURCE_COUNT);
        printResult(r);

        assertFalse(r.hasRemoval());
        assertEquals(text, r.filteredText());
        assertEquals(3, r.keptSentenceCount(), "空行不计句，三句正文");
    }

    // ---------- 人读日志 ----------

    private static void printInput(String text) {
        System.out.println("---- 输入文本 ----");
        System.out.println(text);
        System.out.println("来源总数=" + SOURCE_COUNT);
    }

    private static void printResult(Result r) {
        System.out.println("---- 输出（人读核对） ----");
        System.out.println("保留 " + r.keptSentenceCount() + " 句，删除 " + r.removedSentenceCount() + " 句");
        for (RemovedSentence removed : r.removedSentences()) {
            System.out.println("  删除[" + removed.reason() + "]: " + removed.sentence());
        }
        System.out.println("过滤后文本:\n" + r.filteredText());
        System.out.println();
    }
}
