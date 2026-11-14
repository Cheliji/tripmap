package com.tripmap.poi.importdb;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQL 语句生成单测（功能设计说明书 §5.1 第④步）。
 *
 * <p>覆盖：UPSERT 形态（冲突键不进 UPDATE 子句、updated_at=NOW()）、
 * 转义口径（单引号双写/反斜杠/NULL 不写空串）、tags JSON 数组、固定列 source/status。</p>
 */
class PoiInsertSqlBuilderTest {

    private ImportablePoi poi(String name, String tagsJson, String brief, String remark) {
        return new ImportablePoi(
                "B0FFFABC", name,
                "风景名胜;公园广场;公园", "110101", "5A景区", "AAAAA",
                "杭州市", "西湖区", "330106",
                "120.13", "30.21",
                "公园乐园", tagsJson,
                "4.5", null, "0", "90",
                "{\"mon\":[{\"open\":\"09:00\",\"close\":\"17:00\"}]}",
                "周一闭馆(法定节假日除外)；周二至周日 09:00-16:30",
                brief, "杭州市西湖区", "0571-12345678", null,
                "杭州西湖风景名胜区", remark);
    }

    @Test
    void upsert形态_冲突键不在更新列() {
        String sql = PoiInsertSqlBuilder.build(poi("花港观鱼", "[\"亲子\"]", "简介", null));
        assertTrue(sql.startsWith("INSERT INTO poi ("));
        assertTrue(sql.contains("ON DUPLICATE KEY UPDATE"));
        assertFalse(sql.contains("amap_id = VALUES(amap_id)")); // 冲突键不进更新列
        assertFalse(sql.contains("created_at = VALUES(created_at)")); // 创建时间只插不更
        assertTrue(sql.contains("updated_at = NOW()"));
        assertTrue(sql.contains("'高德+AI增强'"));
        assertTrue(sql.contains(", 1,")); // status=1
        assertTrue(sql.trim().endsWith(";"));
        // 一条语句一行（无换行的裸语句行便于人工逐条执行）
        assertTrue(sql.lines().count() > 3);
    }

    @Test
    void 字符串转义_单引号双写_反斜杠转义() {
        ImportablePoi p = poi("O'Neil 公园", null,
                "带'引号'和\\反斜杠的简介", "备注'测试");
        String sql = PoiInsertSqlBuilder.build(p);
        assertTrue(sql.contains("'O''Neil 公园'"));
        assertTrue(sql.contains("带''引号''和\\\\反斜杠的简介"));
        assertTrue(sql.contains("'备注''测试'"));
    }

    @Test
    void null一律写NULL_不写空串() {
        ImportablePoi p = poi("name", null, null, null);
        String sql = PoiInsertSqlBuilder.build(p);
        assertFalse(sql.contains("''"));
        assertTrue(sql.contains("NULL"));
        // tags 为 null → NULL；photos 为 null → NULL
        assertTrue(sql.contains(", NULL,"));
    }

    @Test
    void tags_json数组原样进字符串列() {
        String sql = PoiInsertSqlBuilder.build(poi("n", "[\"亲子\",\"湖景\"]", null, null));
        assertTrue(sql.contains("'[\"亲子\",\"湖景\"]'"));
    }

    @Test
    void openHours原样进JSON列() {
        String sql = PoiInsertSqlBuilder.build(poi("n", null, null, null));
        assertTrue(sql.contains("'{\"mon\":[{\"open\":\"09:00\",\"close\":\"17:00\"}]}'"));
    }
}
