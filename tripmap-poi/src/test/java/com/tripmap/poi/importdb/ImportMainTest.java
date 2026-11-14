package com.tripmap.poi.importdb;

import com.alibaba.excel.EasyExcel;
import com.tripmap.poi.excel.EnrichedPoiExcelRow;
import com.tripmap.poi.excel.RawPoiExcelRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 功能三端到端单测（功能设计说明书 §5.1）：enriched Excel → SQL 文件。
 *
 * <p>覆盖：enrich_status=SUCCESS 门槛（FAILED/空值行不导出）、--dry-run 不产文件、
 * SQL 文件结构（文件头/事务包裹/语句条数）、缺省输出命名。</p>
 */
class ImportMainTest {

    @TempDir
    Path tempDir;

    private EnrichedPoiExcelRow row(String amapId, String name, String enrichStatus) {
        EnrichedPoiExcelRow row = EnrichedPoiExcelRow.from(RawPoiExcelRow.builder()
                .amapId(amapId)
                .name(name)
                .typecode("110101")
                .cityname("杭州市")
                .adname("西湖区")
                .location("120.130000,30.210000")
                .build());
        row.setEnrichStatus(enrichStatus);
        row.setCategory("公园乐园");
        row.setOpenHours("{\"mon\":[{\"open\":\"09:00\",\"close\":\"17:00\"}],\"tue\":[],\"wed\":null,\"thu\":null,\"fri\":null,\"sat\":null,\"sun\":null}");
        row.setTags("亲子");
        return row;
    }

    private static void writeExcel(Path path, List<EnrichedPoiExcelRow> rows) {
        EasyExcel.write(path.toString(), EnrichedPoiExcelRow.class)
                .sheet("enriched")
                .doWrite(rows);
    }

    @Test
    void dryRun只校验计数_不生成文件() throws IOException {
        Path input = tempDir.resolve("demo-poi-enriched.xlsx");
        writeExcel(input, List.of(
                row("A", "花港观鱼", "SUCCESS"),
                row("B", "苏堤", "FAILED"),
                row("C", "断桥", null)));

        Path output = tempDir.resolve("demo-poi-import.sql");
        int exit = ImportMain.execute(new ImportMain.ParsedArgs(input.toString(), output.toString(), true));

        assertEquals(0, exit);
        assertFalse(Files.exists(output), "--dry-run 不应生成文件");
    }

    @Test
    void 门槛过滤FAILED与空值_只导出SUCCESS行() throws IOException {
        Path input = tempDir.resolve("demo-poi-enriched.xlsx");
        writeExcel(input, List.of(
                row("A", "花港观鱼", "SUCCESS"),
                row("B", "苏堤", "FAILED"),
                row("C", "断桥", null),
                row("D", "平湖秋月", "SUCCESS")));

        Path output = tempDir.resolve("demo-poi-import.sql");
        int exit = ImportMain.execute(new ImportMain.ParsedArgs(input.toString(), output.toString(), false));

        assertEquals(0, exit);
        String sql = Files.readString(output);
        assertTrue(sql.contains("START TRANSACTION;"));
        assertTrue(sql.contains("COMMIT;"));
        assertTrue(sql.contains("花港观鱼"));
        assertTrue(sql.contains("平湖秋月"));
        assertFalse(sql.contains("苏堤"), "FAILED 行不导出");
        assertFalse(sql.contains("断桥"), "enrich_status 空行不导出");
        // 一条语句一个 INSERT
        assertEquals(2, sql.split("INSERT INTO poi", -1).length - 1);
    }

    @Test
    void 校验失败行跳过_文件仍生成() throws IOException {
        Path input = tempDir.resolve("demo-poi-enriched.xlsx");
        EnrichedPoiExcelRow bad = row("E", "坏行", "SUCCESS");
        bad.setOpenHours("{\"mon\":\"全天\"}"); // 非法 open_hours → 跳过
        writeExcel(input, List.of(row("A", "花港观鱼", "SUCCESS"), bad));

        Path output = tempDir.resolve("demo-poi-import.sql");
        int exit = ImportMain.execute(new ImportMain.ParsedArgs(input.toString(), output.toString(), false));

        assertEquals(0, exit);
        String sql = Files.readString(output);
        assertEquals(1, sql.split("INSERT INTO poi", -1).length - 1);
    }

    @Test
    void 缺省输出命名_输入同目录import_sql() {
        ImportMain.ParseResult parsed = ImportMain.parseArgs(
                new String[]{"--input", "./data/hangzhou-poi-enriched.xlsx"});
        assertEquals(-1, parsed.exitCode());
        assertEquals(".\\data\\hangzhou-poi-import.sql", parsed.args().output().replace('/', '\\'));
    }

    @Test
    void 输入不可读_退出码1() {
        int exit = ImportMain.execute(new ImportMain.ParsedArgs(
                tempDir.resolve("not-exist.xlsx").toString(),
                tempDir.resolve("out.sql").toString(), false));
        assertEquals(1, exit);
    }
}
