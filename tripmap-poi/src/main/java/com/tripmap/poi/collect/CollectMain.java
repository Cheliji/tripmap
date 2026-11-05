package com.tripmap.poi.collect;

import com.alibaba.excel.EasyExcel;
import com.tripmap.poi.excel.RawPoiExcelRow;
import com.tripmap.tool.amap.AmapClient;
import com.tripmap.tool.amap.AmapProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 功能一采集脚本入口（任务 1.3.2，设计说明书 §3.1/§3.3/§3.5）
 *
 * <p>单城市批量采集高德 POI，产出 {@code {city}-poi-raw.xlsx} 快照：</p>
 *
 * <pre>{@code
 * java com.tripmap.poi.collect.CollectMain --city 杭州 --output ./data/hangzhou-poi-raw.xlsx
 * }</pre>
 *
 * <ul>
 *   <li><b>幂等可重跑</b>：重复执行生成同等语义的新快照（去重主键 amap_id 相同，字段以最新采集为准，§3.5）；</li>
 *   <li><b>零 Spring</b>：不读 application.yml，配置只读环境变量，{@code AmapClient} 直接 new（§3.5）；</li>
 *   <li><b>密钥 fail-fast</b>：{@code AMAP_API_KEY} 缺失即报错退出（AGENTS.md 第 5 节，禁止硬编码）；</li>
 *   <li><b>退出码</b>：0 成功；1 运行时失败（城市未定义/密钥缺失/写盘失败）；2 参数用法错误。</li>
 * </ul>
 */
public class CollectMain {

    private static final Logger log = LoggerFactory.getLogger(CollectMain.class);

    /** 组合间最小调用间隔（§3.3 建议 100ms，QPS 令牌桶之外的兜底） */
    private static final long COMBO_INTERVAL_MS = 100L;

    public static void main(String[] args) {
        int exitCode = run(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String[] args) {
        // ---- 1. 参数解析 ----
        String city = null;
        String output = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--city" -> city = (i + 1 < args.length) ? args[++i] : null;
                case "--output" -> output = (i + 1 < args.length) ? args[++i] : null;
                case "--help", "-h" -> {
                    printUsage();
                    return 0;
                }
                default -> {
                    System.err.println("未知参数: " + args[i]);
                    printUsage();
                    return 2;
                }
            }
        }
        if (!StringUtils.hasText(city)) {
            System.err.println("缺少必填参数 --city");
            printUsage();
            return 2;
        }
        if (!StringUtils.hasText(output)) {
            // §3.1：--output 缺省 ./data/{city}-poi-raw.xlsx
            output = "./data/" + city + "-poi-raw.xlsx";
        }

        // ---- 2. 加载采集矩阵（1.3.1）----
        CityCollectMatrix matrix;
        try {
            matrix = new CollectMatrixLoader().loadDefault(city);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.error("采集矩阵加载失败: {}", e.getMessage());
            return 1;
        }

        // ---- 3. 构建高德客户端（§3.5：环境变量 + new 构造，fail-fast）----
        AmapProperties properties = new AmapProperties();
        properties.setApiKey(System.getenv("AMAP_API_KEY"));
        // 可选环境变量：AMAP_QPS（默认 3）、AMAP_BASE_URL（联调用，默认官方域名）
        String qpsEnv = System.getenv("AMAP_QPS");
        if (StringUtils.hasText(qpsEnv)) {
            properties.setQps(Double.parseDouble(qpsEnv.trim()));
        }
        String baseUrlEnv = System.getenv("AMAP_BASE_URL");
        if (StringUtils.hasText(baseUrlEnv)) {
            properties.setBaseUrl(baseUrlEnv.trim());
        }
        AmapClient amapClient;
        try {
            amapClient = new AmapClient(AmapClient.buildRestClient(properties), properties);
        } catch (IllegalStateException e) {
            log.error("高德客户端初始化失败: {}", e.getMessage());
            return 1;
        }

        // ---- 4. 采集（§3.3：组合级容错 + amap_id 去重）----
        log.info("开始采集 [{}]：{} 分区 × {} 分类 = {} 个组合",
                matrix.city(), matrix.districts().size(), matrix.categories().size(),
                matrix.combos().size());
        PoiCollector.CollectResult result;
        try {
            result = new PoiCollector(new AmapPoiSearcher(amapClient))
                    .collect(matrix, COMBO_INTERVAL_MS);
        } catch (RuntimeException e) {
            log.error("采集过程异常中断", e);
            return 1;
        }

        // ---- 5. 写出 Excel 快照（§3.4 列定义）----
        // collected_at 取快照级时间（ISO-8601）：同一份快照内全表一致，便于归档比对（§2 文件即持久化）
        String collectedAt = Instant.now().toString();
        List<RawPoiExcelRow> rows = new ArrayList<>(result.poiCount());
        for (PoiCollector.CollectedPoi collected : result.pois()) {
            rows.add(RawPoiExcelRow.of(collected.getPoi(), collected.hitKeywordsJoined(), collectedAt));
        }
        try {
            Path outputPath = Path.of(output);
            if (outputPath.getParent() != null) {
                Files.createDirectories(outputPath.getParent());
            }
            EasyExcel.write(outputPath.toString(), RawPoiExcelRow.class)
                    .sheet("raw")
                    .doWrite(rows);
        } catch (Exception e) {
            log.error("Excel 快照写出失败: {}", output, e);
            return 1;
        }

        // ---- 6. 汇总（§3.3 结束时输出汇总：成功 X 条 / 失败 Y 个查询 / 耗时 Z s）----
        log.info("采集完成：成功 {} 条（去重后）/ 失败 {} 个查询 / 共 {} 个查询 / 耗时 {} s，快照: {}",
                result.poiCount(), result.failedQueries(), result.totalQueries(),
                result.elapsedMs() / 1000, output);
        return 0;
    }

    private static void printUsage() {
        System.err.println("""
                用法: java com.tripmap.poi.collect.CollectMain --city <城市名> [--output <xlsx路径>]
                  --city    必填，城市名（如 杭州）；矩阵未定义的城市请先补录
                            resources/keywords/collect-matrix.json（设计说明书 §3.1）
                  --output  选填，输出 Excel 路径，缺省 ./data/{city}-poi-raw.xlsx
                环境变量: AMAP_API_KEY（必填）；AMAP_QPS、AMAP_BASE_URL（选填）
                """);
    }
}
