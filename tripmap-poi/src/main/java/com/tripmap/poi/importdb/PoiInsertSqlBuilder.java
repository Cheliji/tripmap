package com.tripmap.poi.importdb;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link ImportablePoi} → MySQL UPSERT 语句生成器（功能设计说明书 §5.1 第④步）。
 *
 * <p><b>一条语句对应一个 POI</b>（逐条 UPSERT）：人工执行友好——可逐条查看、单独挑行执行、
 * 报错易定位；300~500 条规模无需拼多行 VALUES。以 {@code uk_amap_id} 唯一键幂等：
 * 同一文件重复执行、修正后重生成再执行都只产生新增/更新。</p>
 *
 * <p><b>方言注记</b>：{@link #UPSERT_SUFFIX} 为 MySQL 8 的
 * {@code ON DUPLICATE KEY UPDATE}；迁 PG 时替换为
 * {@code ON CONFLICT (amap_id) DO UPDATE SET ...}（任务 1.2.5 清单登记，§5.1）。</p>
 *
 * <p>转义口径（§5.1）：单引号 {@code ''} 双写；反斜杠 {@code \\\\} 转义；
 * {@code null} 一律写 {@code NULL}（ImportablePoi 已保证无空串）。</p>
 */
final class PoiInsertSqlBuilder {

    /** UPSERT 子句（方言注记见类注释；迁 PG 改此处一处） */
    private static final String UPSERT_SUFFIX = "ON DUPLICATE KEY UPDATE";

    /** 列清单（与 sql/001_poi.sql 严格一致；amap_id 为冲突键不进 UPDATE 子句） */
    private static final List<String> COLUMNS = List.of(
            "amap_id", "name", "type", "typecode", "keytag", "level",
            "city", "district", "district_adcode", "longitude", "latitude",
            "category", "tags", "rating", "cost", "ticket_price", "suggest_duration",
            "open_hours", "opentime2", "brief", "address", "tel", "photos",
            "official_account", "remark", "source", "status",
            "created_at", "updated_at");

    /** source/status 固定值（§5.2：'高德+AI增强' / 1=正常） */
    private static final String SOURCE = "高德+AI增强";

    private PoiInsertSqlBuilder() {
    }

    /** 生成单行 UPSERT 语句（以分号结尾，一条一行）。 */
    static String build(ImportablePoi p) {
        List<String> values = new ArrayList<>(COLUMNS.size());
        values.add(str(p.amapId()));
        values.add(str(p.name()));
        values.add(str(p.type()));
        values.add(str(p.typecode()));
        values.add(str(p.keytag()));
        values.add(str(p.level()));
        values.add(str(p.city()));
        values.add(str(p.district()));
        values.add(num(p.districtAdcode()));
        values.add(num(p.longitude()));
        values.add(num(p.latitude()));
        values.add(str(p.category()));
        values.add(str(p.tagsJson()));
        values.add(num(p.rating()));
        values.add(num(p.cost()));
        values.add(num(p.ticketPrice()));
        values.add(num(p.suggestDuration()));
        values.add(str(p.openHours()));
        values.add(str(p.opentime2()));
        values.add(str(p.brief()));
        values.add(str(p.address()));
        values.add(str(p.tel()));
        values.add(str(p.photos()));
        values.add(str(p.officialAccount()));
        values.add(str(p.remark()));
        values.add(str(SOURCE));
        values.add("1");
        values.add("NOW()");
        values.add("NOW()");

        StringBuilder sb = new StringBuilder(256);
        sb.append("INSERT INTO poi (").append(String.join(", ", COLUMNS)).append(")\nVALUES (")
                .append(String.join(", ", values)).append(")\n").append(UPSERT_SUFFIX).append('\n');
        for (int i = 0; i < COLUMNS.size(); i++) {
            String col = COLUMNS.get(i);
            if ("amap_id".equals(col) || "created_at".equals(col)) {
                continue; // 冲突键与创建时间不在更新列内
            }
            sb.append("  ").append(col).append(" = ")
                    .append("updated_at".equals(col) ? "NOW()" : "VALUES(" + col + ")");
            sb.append(i == COLUMNS.size() - 1 ? ";" : ",\n");
        }
        return sb.toString();
    }

    /** SQL 字符串字面量：单引号双写 + 反斜杠转义（§5.1） */
    private static String str(String value) {
        if (value == null) {
            return "NULL";
        }
        return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'";
    }

    /** 数值列：校验期已归一为纯文本数值串，原样输出不加引号 */
    private static String num(String value) {
        return value == null ? "NULL" : value;
    }
}
