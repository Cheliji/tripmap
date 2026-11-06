package com.tripmap.poi.enrich;

import com.tripmap.poi.excel.EnrichedPoiExcelRow;
import com.tripmap.poi.excel.RawPoiExcelRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 断点续跑与快照写出单测（tripmap-poi功能设计说明书 §4.9）
 *
 * <p>核心语义（输出 Excel 即检查点）：SUCCESS 行冻结——按读回字符串值原样保留
 *（含人工 reviewed=y 与手改 open_hours），绝不重新生成；FAILED/空缺行重跑整行覆盖；
 * STALE 行（输出有输入无）保留并排在末尾；行顺序恒等于输入顺序；
 * 失败后退出码仍为 0（部分 FAILED 属预期行为）。</p>
 */
class EnrichMainTest {

    @TempDir
    Path tempDir;

    private RawPoiExcelRow raw(String amapId, String name) {
        return RawPoiExcelRow.builder()
                .amapId(amapId)
                .name(name)
                .cityname("杭州市")
                .adname("西湖区")
                .typecode("110101")
                .build();
    }

    private RowEnricher.RowOutcome successOf(RawPoiExcelRow raw) {
        EnrichedPoiExcelRow row = EnrichedPoiExcelRow.from(raw);
        row.setEnrichStatus("SUCCESS");
        row.setCategory("公园乐园");
        row.setSummary("机器生成摘要");
        return new RowEnricher.RowOutcome(row, null, null);
    }

    private int runWith(Path input, Path output, EnrichMain.RowProcessor processor) {
        EnrichMain.ParsedArgs args = new EnrichMain.ParsedArgs(
                input.toString(), output.toString(), 0L);
        return EnrichMain.execute(args, processor);
    }

    @Test
    void SUCCESS行冻结原样保留含人工修改_FAILED行覆盖_新行处理_STALE保留() throws Exception {
        Path input = tempDir.resolve("hangzhou-poi-raw.xlsx");
        Path output = tempDir.resolve("hangzhou-poi-enriched.xlsx");
        EasyExcelWrite.write(input, List.of(raw("A", "花港观鱼"), raw("B", "苏堤"), raw("C", "断桥")));

        // 已有输出：A 人工校对过（reviewed=y + 手改 open_hours）；B 上轮 FAILED；D 为 STALE
        EnrichedPoiExcelRow frozenA = EnrichedPoiExcelRow.from(raw("A", "花港观鱼"));
        frozenA.setEnrichStatus("SUCCESS");
        frozenA.setOpenHours("{\"mon\":[{\"open\":\"08:00\",\"close\":\"18:00\"}],\"tue\":[],\"wed\":null,\"thu\":null,\"fri\":null,\"sat\":null,\"sun\":null}");
        frozenA.setReviewed("y");
        EnrichedPoiExcelRow failedB = EnrichedPoiExcelRow.from(raw("B", "苏堤"));
        failedB.setEnrichStatus("FAILED");
        EnrichedPoiExcelRow staleD = EnrichedPoiExcelRow.from(raw("D", "孤山"));
        staleD.setEnrichStatus("SUCCESS");
        staleD.setReviewed("y");
        EasyExcelWrite.write(output, List.of(frozenA, failedB, staleD));

        int exit = runWith(input, output, this::successOf);

        assertEquals(0, exit);
        List<EnrichedPoiExcelRow> rows = EnrichMain.readEnriched(output);
        assertEquals(4, rows.size(), "A/B/C 输入行 + D STALE 行");

        // 行顺序：输入顺序 + STALE 末尾
        assertEquals(List.of("A", "B", "C", "D"), rows.stream().map(EnrichedPoiExcelRow::getAmapId).toList());

        // A 冻结：人工修改一字不差（read-back 字符串值原样照抄，未经任何重生成）
        EnrichedPoiExcelRow a = rows.get(0);
        assertEquals("SUCCESS", a.getEnrichStatus());
        assertEquals("y", a.getReviewed());
        assertTrue(a.getOpenHours().contains("08:00"), "人工修改的 open_hours 必须存活");
        assertNull(a.getSummary(), "冻结行不应被机器摘要填充");

        // B 覆盖：占位行被处理器结果替换
        assertEquals("机器生成摘要", rows.get(1).getSummary());
        assertEquals("SUCCESS", rows.get(1).getEnrichStatus());

        // C 正常处理
        assertEquals("机器生成摘要", rows.get(2).getSummary());

        // D STALE 保留
        assertEquals("y", rows.get(3).getReviewed());
        assertEquals("孤山", rows.get(3).getName());
    }

    @Test
    void 处理器返回FAILED行结果落盘且退出码为0() throws Exception {
        Path input = tempDir.resolve("demo-poi-raw.xlsx");
        Path output = tempDir.resolve("demo-poi-enriched.xlsx");
        EasyExcelWrite.write(input, List.of(raw("A", "花港观鱼"), raw("B", "苏堤")));

        int exit = runWith(input, output, r -> {
            if (r.getAmapId().equals("B")) {
                EnrichedPoiExcelRow row = EnrichedPoiExcelRow.from(r);
                row.setEnrichStatus("FAILED");
                return new RowEnricher.RowOutcome(row, "SEARCH", "搜索服务超时");
            }
            return successOf(r);
        });

        assertEquals(0, exit, "部分 FAILED 属预期行为，退出码应为 0");
        List<EnrichedPoiExcelRow> rows = EnrichMain.readEnriched(output);
        assertEquals("SUCCESS", rows.get(0).getEnrichStatus());
        assertEquals("FAILED", rows.get(1).getEnrichStatus());
        assertEquals("苏堤", rows.get(1).getName(), "FAILED 行仍保留 raw 19 列");
    }

    @Test
    void 输入重复amap_id保留首条() throws Exception {
        Path input = tempDir.resolve("demo-poi-raw.xlsx");
        Path output = tempDir.resolve("demo-poi-enriched.xlsx");
        EasyExcelWrite.write(input, List.of(raw("A", "首个"), raw("A", "重复")));

        int exit = runWith(input, output, this::successOf);

        assertEquals(0, exit);
        List<EnrichedPoiExcelRow> rows = EnrichMain.readEnriched(output);
        assertEquals(1, rows.size());
        assertEquals("首个", rows.get(0).getName());
    }

    @Test
    void 快照写出后临时文件不残留() throws Exception {
        Path input = tempDir.resolve("demo-poi-raw.xlsx");
        Path output = tempDir.resolve("demo-poi-enriched.xlsx");
        EasyExcelWrite.write(input, List.of(raw("A", "花港观鱼")));

        runWith(input, output, this::successOf);

        assertTrue(java.nio.file.Files.exists(output));
        assertNotNull(rowsReadable(output));
        try (var stream = java.nio.file.Files.list(tempDir)) {
            assertTrue(stream.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")),
                    "原子移动后不应残留 .tmp 文件");
        }
    }

    private List<EnrichedPoiExcelRow> rowsReadable(Path output) {
        return EnrichMain.readEnriched(output);
    }

    /** 测试内写 Excel 的小工具（与生产路径同用 EasyExcel） */
    private static final class EasyExcelWrite {
        static void write(Path path, List<? extends RawPoiExcelRow> rows) {
            com.alibaba.excel.EasyExcel.write(path.toString(), rows.get(0).getClass())
                    .sheet("raw")
                    .doWrite(new java.util.ArrayList<>(rows));
        }
    }
}
