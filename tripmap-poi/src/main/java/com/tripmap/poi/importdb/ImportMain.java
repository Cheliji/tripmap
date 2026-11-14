package com.tripmap.poi.importdb;

import com.alibaba.excel.EasyExcel;
import com.tripmap.poi.excel.EnrichedPoiExcelRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 功能三入口：enriched Excel → SQL 导入文件（任务 1.3.6 脚本侧，功能设计说明书 §5.1）。
 *
 * <pre>{@code
 * java com.tripmap.poi.importdb.ImportMain --input ./data/hangzhou-poi-enriched.xlsx \
 *     [--output ./data/hangzhou-poi-import.sql] [--dry-run]
 * }</pre>
 *
 * <p><b>零数据库依赖</b>（v1.5）：脚本不连接 MySQL，只生成
 * {@code INSERT ... ON DUPLICATE KEY UPDATE} 的 .sql 文件（{@link PoiInsertSqlBuilder}），
 * 由人工在 MySQL 客户端自行执行（文件已带 {@code START TRANSACTION; ... COMMIT;} 包裹，
 * 执行前需先跑 {@code sql/001_poi.sql} 建表）。</p>
 *
 * <p><b>导入门槛</b>（v1.5）：仅 {@code enrich_status=SUCCESS} 的行进入导出
 * （人工校对列 reviewed 保留在 Excel 中但不再是门槛，§4.11）；FAILED/空值行跳过并计入清单。
 * 文件内按 amap_id 去重（保留首行）。校验与映射见 {@link PoiImportRowMapper}。</p>
 *
 * <p>退出码：0 跑完（允许跳过）；1 运行时失败（输入不可读/写盘失败）；2 参数用法错误。</p>
 */
public class ImportMain {

    private static final Logger log = LoggerFactory.getLogger(ImportMain.class);

    record ParsedArgs(String input, String output, boolean dryRun) {
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
        return execute(parsed.args());
    }

    /** 主执行体（包级可见，供单测注入临时文件路径） */
    static int execute(ParsedArgs args) {
        // ---- 1. 读 enriched Excel ----
        List<EnrichedPoiExcelRow> rows;
        try {
            rows = readEnriched(Path.of(args.input()));
        } catch (Exception e) {
            log.error("enriched 输入读取失败: {}", args.input(), e);
            return 1;
        }

        // ---- 2. 过滤 enrich_status=SUCCESS（v1.5 门槛）+ amap_id 文件内去重 ----
        List<EnrichedPoiExcelRow> candidates = new ArrayList<>(rows.size());
        List<String> skipped = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        int notSuccess = 0;
        for (EnrichedPoiExcelRow row : rows) {
            if (!"SUCCESS".equals(row.getEnrichStatus())) {
                notSuccess++;
                continue;
            }
            String id = row.getAmapId();
            if (!StringUtils.hasText(id)) {
                skipped.add("enrich_status=SUCCESS 但 amap_id 为空: name=" + row.getName());
                continue;
            }
            if (!seenIds.add(id.trim())) {
                log.warn("文件内 amap_id 重复，保留首行: amap_id={}", id);
                continue;
            }
            candidates.add(row);
        }
        if (notSuccess > 0) {
            log.info("门槛过滤（enrich_status≠SUCCESS）跳过 {} 行（§4.11）", notSuccess);
        }

        // ---- 3. 逐行映射 + 校验（防线 2，§5.1 第③步）----
        List<String> statements = new ArrayList<>(candidates.size());
        for (EnrichedPoiExcelRow row : candidates) {
            PoiImportRowMapper.MappingResult result = PoiImportRowMapper.map(row);
            for (String warning : result.warnings()) {
                log.warn("{}", warning);
            }
            if (result.error() != null) {
                skipped.add(result.error());
                continue;
            }
            statements.add(PoiInsertSqlBuilder.build(result.poi()));
        }

        // ---- 4. 汇总（§5.1 第⑤步）----
        log.info("导入 SQL 生成：应导出 {} 条 / 跳过 {} 条", statements.size(), skipped.size());
        if (!skipped.isEmpty()) {
            log.warn("跳过清单（{} 条）：\n{}", skipped.size(), String.join("\n", skipped));
        }
        if (args.dryRun()) {
            log.info("--dry-run：仅校验计数，未生成文件");
            return 0;
        }

        // ---- 5. 写出 .sql（临时文件 + 移动，防中途截断）----
        Path output = Path.of(args.output());
        try {
            writeSqlFile(output, args.input(), statements);
        } catch (IOException e) {
            log.error("SQL 文件写出失败: {}", args.output(), e);
            return 1;
        }
        log.info("SQL 文件已生成: {}（请在 MySQL 客户端执行；执行前确保已跑 sql/001_poi.sql 建表）", args.output());
        return 0;
    }

    private static void writeSqlFile(Path output, String input, List<String> statements) throws IOException {
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        String generatedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        StringBuilder sb = new StringBuilder(statements.size() * 256 + 512);
        sb.append("-- tripmap poi 导入 SQL（由 ImportMain 生成，勿手改；重跑脚本可整体覆盖）\n")
                .append("-- 源文件: ").append(input).append('\n')
                .append("-- 生成时间: ").append(generatedAt).append('\n')
                .append("-- 语句条数: ").append(statements.size())
                .append("（INSERT ... ON DUPLICATE KEY UPDATE，uk_amap_id 幂等，可重复执行）\n")
                .append("-- 前置: 已执行 sql/001_poi.sql 建表；执行失败整体回滚（事务包裹）\n")
                .append('\n')
                .append("START TRANSACTION;\n\n");
        for (String statement : statements) {
            sb.append(statement).append('\n');
        }
        sb.append("\nCOMMIT;\n");
        Path tmp = output.resolveSibling(output.getFileName() + ".tmp");
        Files.writeString(tmp, sb.toString());
        try {
            Files.move(tmp, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, output, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @SuppressWarnings("unchecked")
    static List<EnrichedPoiExcelRow> readEnriched(Path input) {
        return EasyExcel.read(input.toString())
                .head(EnrichedPoiExcelRow.class)
                .sheet()
                .doReadSync().stream()
                .map(row -> (EnrichedPoiExcelRow) row)
                .toList();
    }

    record ParseResult(ParsedArgs args, int exitCode) {
        /** exitCode >= 0 表示直接返回该码（参数错误/帮助），-1 表示解析成功 */
    }

    static ParseResult parseArgs(String[] args) {
        String input = null;
        String output = null;
        boolean dryRun = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--input" -> input = (i + 1 < args.length) ? args[++i] : null;
                case "--output" -> output = (i + 1 < args.length) ? args[++i] : null;
                case "--dry-run" -> dryRun = true;
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
            // 缺省：输入同目录 {city}-poi-import.sql（§5.1）
            Path inputPath = Path.of(input);
            String fileName = inputPath.getFileName().toString();
            String base = fileName.replaceAll("(?i)\\.xlsx$", "");
            String outName = base.contains("poi-enriched")
                    ? base.replaceAll("(?i)poi-enriched", "poi-import.sql")
                    : base + "-import.sql";
            Path parent = inputPath.getParent();
            output = (parent != null ? parent : Path.of(".")).resolve(outName).toString();
        }
        return new ParseResult(new ParsedArgs(input, output, dryRun), -1);
    }

    private static void printUsage() {
        System.err.println("""
                用法: java com.tripmap.poi.importdb.ImportMain --input <enriched.xlsx> [--output <import.sql>] [--dry-run]
                  --input     必填，功能二产出 {city}-poi-enriched.xlsx
                  --output    选填，缺省输入同目录 {city}-poi-import.sql
                  --dry-run   选填，只校验和计数，不生成文件
                门槛: 仅导出 enrich_status=SUCCESS 的行（v1.5，不再看 reviewed 列）
                前置: 人工执行前需先跑 sql/001_poi.sql 建表；脚本零数据库连接
                """);
    }
}
