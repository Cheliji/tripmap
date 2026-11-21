package com.tripmap.tool.search.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "无来源事实句删除"后处理器（TASKS 2.7.2，设计说明书 §2.1/§14：
 * 内容全部以联网搜索为准并强制引用来源，无来源事实句删除）。
 *
 * <p><b>攻略节点输出契约</b>（LLM 侧，确定性代码只做校验不做生成）：</p>
 * <ul>
 *   <li>事实句末尾必须挂引用标记 {@code [n]}，n 为 {@code WebSearchOutput.results}
 *       的来源序号（从 1 开始）；一句可挂多个标记；</li>
 *   <li>非事实句（标题、过渡、问候、行程安排的复述性文字）无需引用；</li>
 *   <li>引用只标序号不带 URL——URL 由前端按序号从来源清单渲染。</li>
 * </ul>
 *
 * <p><b>后处理规则</b>（确定性代码执行，零 LLM）：</p>
 * <ol>
 *   <li>按行与句末标点（。！？!?；;）切分为句子，保留原换行结构；</li>
 *   <li>事实句判定（启发式）：含阿拉伯数字 <b>或</b> 事实关键词
 *       （价格/时长/开放/预约等，见 {@link #FACT_PATTERN}）。Markdown 标题行
 *       （{@code #} 开头）永远视为非事实句；</li>
 *   <li>事实句无任何 {@code [n]} 标记 → 删除（原因：事实句缺少来源引用）；</li>
 *   <li>事实句引用序号越界（n &lt; 1 或 n &gt; 来源总数）→ 删除
 *       （原因：引用序号越界——防 LLM 幻觉出不存在的来源）；</li>
 *   <li>被删句子连同原因记入 {@link Result#removedSentences()}，供评测节点
 *       （M5）与人工 review 核对；删除动作只在此处发生，攻略节点不得自行删句。</li>
 * </ol>
 *
 * <p>纯静态工具类，零 Spring；攻略节点（M3 任务 3.7）在 LLM 输出后调用本过滤器。</p>
 */
public final class UncitedFactSentenceFilter {

    private static final Logger log = LoggerFactory.getLogger(UncitedFactSentenceFilter.class);

    /** 句末切分：按 。！？!?；; 与换行切分，标点保留在句尾（lookbehind） */
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[。！？!?；;])");

    /** 引用标记：[n]，n 为 1~3 位数字（来源序号） */
    private static final Pattern CITATION = Pattern.compile("\\[(\\d{1,3})\\]");

    /**
     * 事实句触发模式：阿拉伯数字（时间/价格/日期/数量）或事实关键词
     * （金额/折扣/距离/时长/开放状态/预约要求/禁令等——这类断言错了成本高，
     * 必须可溯源，设计说明书 §14）。建议/过渡类文字不触发。
     */
    private static final Pattern FACT_PATTERN = Pattern.compile(
            "\\d|元|折|公里|分钟|小时|开放|闭馆|营业|预约|门票|禁止|免费|截止|限流|关闭");

    private UncitedFactSentenceFilter() {
    }

    /**
     * 执行"无来源事实句删除"。
     *
     * @param text        攻略节点输出的完整文本（允许 null/空，视为无内容）
     * @param sourceCount 本次引用来源总数（{@code WebSearchOutput.results.size()}），
     *                    用于核对引用序号上界；&lt;0 时按 0 处理
     * @return 过滤结果（新文本 + 被删句子清单 + 统计），不修改入参
     */
    public static Result filter(String text, int sourceCount) {
        int upperBound = Math.max(0, sourceCount);
        if (text == null || text.isBlank()) {
            return new Result("", List.of(), 0, 0);
        }

        List<String> keptLines = new ArrayList<>();
        List<RemovedSentence> removed = new ArrayList<>();
        int keptCount = 0;

        for (String line : text.split("\n", -1)) {
            if (line.trim().isEmpty()) {
                keptLines.add(line);
                continue;
            }
            boolean heading = line.trim().startsWith("#");
            StringBuilder keptLine = new StringBuilder();
            for (String sentence : SENTENCE_END.split(line)) {
                if (sentence.isBlank()) {
                    continue;
                }
                String verdict = heading ? null : check(sentence, upperBound);
                if (verdict == null) {
                    keptLine.append(sentence);
                    keptCount++;
                } else {
                    removed.add(new RemovedSentence(sentence.trim(), verdict));
                }
            }
            keptLines.add(keptLine.toString());
        }

        String filteredText = String.join("\n", keptLines);
        printReport(filteredText, removed, upperBound);
        return new Result(filteredText, List.copyOf(removed), keptCount, removed.size());
    }

    /**
     * 单句核查。
     *
     * @return null=保留；非 null=删除原因
     */
    private static String check(String sentence, int sourceCount) {
        boolean factual = FACT_PATTERN.matcher(sentence).find();
        Matcher citationMatcher = CITATION.matcher(sentence);
        if (!factual) {
            return null;
        }
        if (!citationMatcher.find()) {
            return "事实句缺少来源引用";
        }
        // 有引用但事实句仍可能被幻觉来源污染：逐个核对序号上界
        do {
            int n = Integer.parseInt(citationMatcher.group(1));
            if (n < 1 || n > sourceCount) {
                return "引用序号越界([" + n + "]，来源总数 " + sourceCount + ")";
            }
        } while (citationMatcher.find());
        return null;
    }

    private static void printReport(String filteredText, List<RemovedSentence> removed, int sourceCount) {
        StringBuilder sb = new StringBuilder("\n---------- 无来源事实句删除报告（来源总数=")
                .append(sourceCount).append("） ----------\n");
        if (removed.isEmpty()) {
            sb.append("无删除：全部事实句均带合法引用 ✔\n");
        } else {
            sb.append(String.format("共删除 %d 句：%n", removed.size()));
            sb.append(String.format("%-4s %-8s %s%n", "序号", "原因", "被删句子"));
            for (int i = 0; i < removed.size(); i++) {
                RemovedSentence r = removed.get(i);
                sb.append(String.format("%-4d %-8s %s%n", i + 1, r.reason(), abbreviate(r.sentence(), 60)));
            }
        }
        sb.append("---- 过滤后文本（前 200 字） ----\n").append(abbreviate(filteredText, 200)).append('\n');
        log.info(sb.toString());
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "-";
        }
        String flattened = s.replace("\n", "⏎");
        return flattened.length() <= max ? flattened : flattened.substring(0, max - 1) + "…";
    }

    /** 过滤结果 */
    public record Result(String filteredText, List<RemovedSentence> removedSentences,
                         int keptSentenceCount, int removedSentenceCount) {

        /** 是否有删除（评测节点 M5 与人工 review 的快速信号） */
        public boolean hasRemoval() {
            return !removedSentences.isEmpty();
        }
    }

    /** 被删句子及删除原因（可追溯，不静默丢弃） */
    public record RemovedSentence(String sentence, String reason) {
    }
}
