package com.tripmap.poi.enrich;

import com.alibaba.excel.EasyExcel;
import com.tripmap.poi.excel.EnrichedPoiExcelRow;
import com.tripmap.poi.excel.RawPoiExcelRow;
import com.tripmap.tool.deepseek.DeepSeekChatClient;
import com.tripmap.tool.deepseek.DeepSeekProperties;
import com.tripmap.tool.deepseek.DeepSeekSearchClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 功能二增强脚本入口（任务 1.3.4，tripmap-poi功能设计说明书 §4.9）：
 *
 * <pre>{@code
 * java com.tripmap.poi.enrich.EnrichMain --input ./data/hangzhou-poi-raw.xlsx \
 *     --output ./data/hangzhou-poi-enriched.xlsx
 * }</pre>
 *
 * <p><b>断点续跑</b>（输出 Excel 即检查点，无独立状态文件）：启动时读回已有输出，
 * {@code enrich_status=SUCCESS} 的行<b>冻结</b>——按读回字符串值原样保留（含人工修改的
 * reviewed/open_hours），不重新生成、不重复消耗 API 配额；FAILED/空缺行本轮重跑并整行覆盖；
 * 输出里有但输入没有的行（STALE）保留并 warn。行顺序恒等于输入顺序（STALE 排末尾），
 * 重复运行文件 diff 最小。</p>
 *
 * <p>运行约定：人工修正 FAILED 行后应顺手将 enrich_status 改为 SUCCESS，
 * 否则下次重跑会被机器结果覆盖。不应对同一输出文件并发运行两个实例（无锁，后写胜出）。</p>
 *
 * <p>逐条慢循环：每条 POI = 1 次搜索 + 1 次 LLM（§4.2），调用间隔可配（默认 ≥2s）；
 * 每行处理完成即<b>全量重写</b>输出（临时文件 + 原子移动），中断后已完成行全部存活。
 * 结束时输出失败清单（amap_id + 失败步骤 + 原因）供人工处理。</p>
 *
 * <p>退出码：0 跑完（允许部分行 FAILED）；1 运行时失败（密钥缺失/输入不可读/写盘失败）；
 * 2 参数用法错误。零高德 API 调用（§4.2）。</p>
 */
public class EnrichMain {

    private static final Logger log = LoggerFactory.getLogger(EnrichMain.class);

    /** 默认调用间隔（毫秒，§4.9 建议 ≥2s） */
    private static final long DEFAULT_INTERVAL_MS = 2000L;
    /** 进度日志批次（§4.9：每处理 10 条输出进度） */
    private static final int PROGRESS_BATCH = 10;

    /** 行处理器（RowEnricher 实现；测试注入桩） */
    interface RowProcessor {
        RowEnricher.RowOutcome process(RawPoiExcelRow raw);
    }

    record ParsedArgs(String input, String output, long intervalMs) {
    }

    public static void main(String[] args) {
        int exitCode = run(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String[] args) {
        ParseResult parsed = parseArgs(args);
        if (parsed.exitCode() >= 0) {
            return parsed.exitCode();
        }

        // ---- DeepSeek 客户端（§6：配置只读环境变量，密钥缺失 fail-fast）----
        DeepSeekProperties properties = new DeepSeekProperties();
        properties.setApiKey(System.getenv("DEEPSEEK_API_KEY"));
        String baseUrlEnv = System.getenv("DEEPSEEK_BASE_URL");
        if (StringUtils.hasText(baseUrlEnv)) {
            properties.setBaseUrl(baseUrlEnv.trim());
        }
        String modelEnv = System.getenv("DEEPSEEK_MODEL");
        if (StringUtils.hasText(modelEnv)) {
            properties.setChatModel(modelEnv.trim());
        }
        // 可选：搜索时间范围过滤（值以 DeepSeek 官方文档为准；接口静默忽略未知参数，启用前需核对）
        String timeRangeEnv = System.getenv("DEEPSEEK_SEARCH_TIME_RANGE");
        if (StringUtils.hasText(timeRangeEnv)) {
            properties.setSearchTimeRange(timeRangeEnv.trim());
        }

        RowEnricher enricher;
        try {
            DeepSeekSearchClient searchClient =
                    new DeepSeekSearchClient(DeepSeekSearchClient.buildRestClient(properties), properties);
            DeepSeekChatClient chatClient =
                    new DeepSeekChatClient(DeepSeekChatClient.buildRestClient(properties), properties);
            enricher = new RowEnricher(searchClient, chatClient,
                    DistrictAdcodeMapping.loadDefault(), CategoryMapping.loadDefault());
        } catch (IllegalStateException | java.io.UncheckedIOException e) {
            log.error("初始化失败: {}", e.getMessage());
            return 1;
        }
        return execute(parsed.args(), enricher::enrich);
    }

    /**
     * 主执行体（包级可见，供单测注入 {@link RowProcessor} 桩）。
     *
     * @return 0 跑完（允许部分 FAILED）；1 运行时失败
     */
    static int execute(ParsedArgs args, RowProcessor processor) {
        log.info("运行约定：enrich_status=SUCCESS 的行冻结不碰（含人工修改）；FAILED/空缺行本轮重跑并覆盖");

        // ---- 1. 读 raw 输入（amap_id 去重兜底：功能一已去重，此处仅 warn 防呆）----
        List<RawPoiExcelRow> rawRows;
        try {
            rawRows = readRaw(Path.of(args.input()));
        } catch (Exception e) {
            log.error("raw 输入读取失败: {}", args.input(), e);
            return 1;
        }
        List<RawPoiExcelRow> deduped = new ArrayList<>(rawRows.size());
        Set<String> seenIds = new HashSet<>();
        for (RawPoiExcelRow raw : rawRows) {
            if (!StringUtils.hasText(raw.getAmapId())) {
                log.warn("输入行 amap_id 为空，已跳过: name={}", raw.getName());
                continue;
            }
            if (!seenIds.add(raw.getAmapId())) {
                log.warn("输入行 amap_id 重复，保留首条: amap_id={}", raw.getAmapId());
                continue;
            }
            deduped.add(raw);
        }

        // ---- 2. 读回已有输出（断点续跑依据）----
        Path outputPath = Path.of(args.output());
        List<EnrichedPoiExcelRow> existingRows = List.of();
        if (Files.exists(outputPath)) {
            try {
                existingRows = readEnriched(outputPath);
                log.info("检测到已有输出 {} 行，按 enrich_status 恢复断点", existingRows.size());
            } catch (Exception e) {
                log.error("已有输出读取失败（无法断点续跑）: {}", args.output(), e);
                return 1;
            }
        }

        // ---- 3. 构建全量状态（行顺序恒等于输入顺序；STALE 排末尾）----
        Map<String, EnrichedPoiExcelRow> existingById = new LinkedHashMap<>();
        for (EnrichedPoiExcelRow row : existingRows) {
            EnrichedPoiExcelRow dup = existingById.put(row.getAmapId(), row);
            if (dup != null) {
                log.warn("已有输出 amap_id 重复，后者覆盖前者: amap_id={}", row.getAmapId());
            }
        }
        Map<String, EnrichedPoiExcelRow> state = new LinkedHashMap<>();
        Set<String> consumed = new HashSet<>();
        int frozen = 0;
        for (RawPoiExcelRow raw : deduped) {
            EnrichedPoiExcelRow existing = existingById.get(raw.getAmapId());
            if (existing != null && "SUCCESS".equals(existing.getEnrichStatus())) {
                state.put(raw.getAmapId(), existing); // 冻结：原样照抄（含人工修改）
                consumed.add(raw.getAmapId());
                frozen++;
            } else {
                state.put(raw.getAmapId(), EnrichedPoiExcelRow.from(raw)); // 占位：PENDING/待覆盖 FAILED
            }
        }
        for (EnrichedPoiExcelRow row : existingRows) {
            if (row.getAmapId() != null && !consumed.contains(row.getAmapId())
                    && !state.containsKey(row.getAmapId())) {
                state.put(row.getAmapId(), row); // STALE：输出有输入无，保留 + warn
                log.warn("STALE 行保留（输出有、输入无）: amap_id={}", row.getAmapId());
            }
        }

        // ---- 4. 待处理行 = 输入中非 SUCCESS 的行 ----
        List<RawPoiExcelRow> pending = new ArrayList<>();
        for (RawPoiExcelRow raw : deduped) {
            if (!"SUCCESS".equals(state.get(raw.getAmapId()).getEnrichStatus())) {
                pending.add(raw);
            }
        }
        log.info("待增强 {} 条（冻结 {} 条 SUCCESS，共 {} 条），调用间隔 {} ms",
                pending.size(), frozen, deduped.size(), args.intervalMs());

        // ---- 5. 初始快照（输出文件始终是全量画面）----
        try {
            writeSnapshot(outputPath, state.values());
        } catch (IOException e) {
            log.error("初始快照写出失败: {}", args.output(), e);
            return 1;
        }

        // ---- 6. 逐条慢循环（§4.9：失败单项不中断）----
        List<String> failures = new ArrayList<>();
        long startNanos = System.nanoTime();
        int processed = 0;
        int failedThisRun = 0;
        for (RawPoiExcelRow raw : pending) {
            RowEnricher.RowOutcome outcome = processor.process(raw);
            state.put(raw.getAmapId(), outcome.row());
            processed++;
            if (!outcome.success()) {
                failedThisRun++;
                failures.add("amap_id=" + raw.getAmapId() + " step=" + outcome.failStep()
                        + " reason=" + outcome.failReason());
            }
            try {
                writeSnapshot(outputPath, state.values());
            } catch (IOException e) {
                log.error("快照写出失败，已处理 {} 条: {}", processed, args.output(), e);
                return 1;
            }
            if (processed % PROGRESS_BATCH == 0 || processed == pending.size()) {
                long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
                long remainingMin = processed == 0 ? -1
                        : (pending.size() - processed) * elapsedMs / processed / 60000;
                log.info("进度: 本轮 {}/{}（失败 {}）/ 冻结 {} / 预计剩余 {} 分钟",
                        processed, pending.size(), failedThisRun, frozen, remainingMin);
            }
            sleep(args.intervalMs());
        }

        // ---- 7. 汇总（§4.9：结束时输出失败清单）----
        long totalFailed = state.values().stream().filter(r -> "FAILED".equals(r.getEnrichStatus())).count();
        log.info("增强完成：总计 {} 条 / 本轮处理 {} 条（失败 {}）/ 累计 FAILED {} 条 / 输出: {}",
                deduped.size(), processed, failedThisRun, totalFailed, args.output());
        if (!failures.isEmpty()) {
            log.warn("本轮失败清单（{} 条）：\n{}", failures.size(), String.join("\n", failures));
        }
        return 0;
    }

    /**
     * 全量重写输出（临时文件 + 原子移动）：即使进程死在写入中途，
     * 旧文件完好，下次启动读到的仍是上一个一致快照。
     */
    static void writeSnapshot(Path output, java.util.Collection<EnrichedPoiExcelRow> rows) throws IOException {
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        Path tmp = output.resolveSibling(output.getFileName() + ".tmp");
        EasyExcel.write(tmp.toString(), EnrichedPoiExcelRow.class)
                .sheet("enriched")
                .doWrite(new ArrayList<>(rows));
        try {
            Files.move(tmp, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, output, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @SuppressWarnings("unchecked")
    static List<RawPoiExcelRow> readRaw(Path input) {
        return EasyExcel.read(input.toString())
                .head(RawPoiExcelRow.class)
                .sheet()
                .doReadSync().stream()
                .map(row -> (RawPoiExcelRow) row)
                .toList();
    }

    @SuppressWarnings("unchecked")
    static List<EnrichedPoiExcelRow> readEnriched(Path output) {
        return EasyExcel.read(output.toString())
                .head(EnrichedPoiExcelRow.class)
                .sheet()
                .doReadSync().stream()
                .map(row -> (EnrichedPoiExcelRow) row)
                .toList();
    }

    record ParseResult(ParsedArgs args, int exitCode) {
        /** exitCode >= 0 表示直接返回该码（参数错误/帮助），-1 表示解析成功 */
    }

    private static ParseResult parseArgs(String[] args) {
        String input = null;
        String output = null;
        Long intervalMs = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--input" -> input = (i + 1 < args.length) ? args[++i] : null;
                case "--output" -> output = (i + 1 < args.length) ? args[++i] : null;
                case "--interval-ms" -> {
                    String value = (i + 1 < args.length) ? args[++i] : null;
                    if (value != null) {
                        try {
                            intervalMs = Long.parseLong(value.trim());
                        } catch (NumberFormatException e) {
                            System.err.println("--interval-ms 必须是整数: " + value);
                            printUsage();
                            return new ParseResult(null, 2);
                        }
                    }
                }
                case "--help", "-h" -> {
                    printUsage();
                    return new ParseResult(null, 0);
                }
                default -> {
                    System.err.println("未知参数: " + args[i]);
                    printUsage();
                    return new ParseResult(null, 2);
                }
            }
        }
        if (!StringUtils.hasText(input)) {
            System.err.println("缺少必填参数 --input");
            printUsage();
            return new ParseResult(null, 2);
        }
        if (!StringUtils.hasText(output)) {
            // 缺省：输入文件名 poi-raw → poi-enriched（§4.9 命名口径），同目录
            Path inputPath = Path.of(input);
            String fileName = inputPath.getFileName().toString();
            String outName = fileName.contains("poi-raw")
                    ? fileName.replace("poi-raw", "poi-enriched")
                    : fileName.replaceAll("\\.xlsx$", "") + "-enriched.xlsx";
            Path parent = inputPath.getParent();
            output = (parent != null ? parent : Path.of(".")).resolve(outName).toString();
        }
        if (intervalMs == null) {
            String env = System.getenv("ENRICH_INTERVAL_MS");
            intervalMs = StringUtils.hasText(env) ? Long.parseLong(env.trim()) : DEFAULT_INTERVAL_MS;
        }
        if (intervalMs < 0) {
            System.err.println("--interval-ms 不允许为负数");
            return new ParseResult(null, 2);
        }
        return new ParseResult(new ParsedArgs(input, output, intervalMs), -1);
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        long deadline = System.nanoTime() + millis * 1_000_000L;
        long remaining;
        while ((remaining = deadline - System.nanoTime()) > 0) {
            java.util.concurrent.locks.LockSupport.parkNanos(remaining);
        }
    }

    private static void printUsage() {
        System.err.println("""
                用法: java com.tripmap.poi.enrich.EnrichMain --input <raw.xlsx> [--output <enriched.xlsx>] [--interval-ms <毫秒>]
                  --input        必填，功能一产出的 {city}-poi-raw.xlsx
                  --output       选填，缺省输入同目录 {city}-poi-enriched.xlsx
                  --interval-ms  选填，行间调用间隔（默认 2000，环境变量 ENRICH_INTERVAL_MS 可配）
                环境变量: DEEPSEEK_API_KEY（必填）；DEEPSEEK_BASE_URL、DEEPSEEK_MODEL、
                          DEEPSEEK_SEARCH_TIME_RANGE（选填，搜索时间范围过滤，取值以 DeepSeek 官方文档为准）
                断点续跑: enrich_status=SUCCESS 的行冻结不碰；FAILED/空缺行重跑并覆盖
                """);
    }
}
