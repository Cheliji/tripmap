package com.tripmap.poi.collect;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 采集脚本入口参数与失败路径测试（任务 1.3.2，设计说明书 §3.1/§3.5）
 *
 * <p>只覆盖不触网的本地路径：用法帮助、缺参、未知参数、未定义城市、
 * 密钥缺失 fail-fast（AGENTS.md 第 5 节）；正常采集路径由
 * {@link PoiCollectorTest} 与 {@link RawPoiExcelRowTest} 组件级覆盖。</p>
 */
class CollectMainTest {

    @Test
    void 帮助参数返回0() {
        assertEquals(0, CollectMain.run(new String[]{"--help"}));
        assertEquals(0, CollectMain.run(new String[]{"-h"}));
    }

    @Test
    void 缺少city或未知参数返回2() {
        assertEquals(2, CollectMain.run(new String[]{}));
        assertEquals(2, CollectMain.run(new String[]{"--output", "./a.xlsx"}));
        assertEquals(2, CollectMain.run(new String[]{"--city"}));
        assertEquals(2, CollectMain.run(new String[]{"--city", "杭州", "--bogus"}));
    }

    @Test
    void 未定义城市返回1() {
        // 矩阵未定义该城市：应提示补录 collect-matrix.json 而非白名单拒绝（§3.1）
        assertEquals(1, CollectMain.run(new String[]{"--city", "不存在的城市"}));
    }

    @Test
    void 密钥缺失failFast返回1() {
        // 杭州矩阵已定义，但本机 CI 环境未注入 AMAP_API_KEY 时应 fail-fast
        if (System.getenv("AMAP_API_KEY") == null || System.getenv("AMAP_API_KEY").isBlank()) {
            assertEquals(1, CollectMain.run(new String[]{"--city", "杭州"}));
        }
    }
}
