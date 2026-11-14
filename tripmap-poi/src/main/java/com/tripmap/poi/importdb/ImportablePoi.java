package com.tripmap.poi.importdb;

/**
 * 功能三入库行（PoiInsertSqlBuilder 的输入）：全部字段已完成校验与类型归一，
 * 以"可直接写入 SQL 的字符串形态"承载——数值列为 {@link BigDecimal#toPlainString()} 结果，
 * {@code null} 表示 SQL NULL（绝不写空串，空串进索引是经典坑，功能设计说明书 §5.1）。
 *
 * @param tagsJson open_hours 同理由校验器保证合法后原样传递；tags 已由分号串转为 JSON 数组文本
 */
record ImportablePoi(
        String amapId, String name,
        String type, String typecode, String keytag, String level,
        String city, String district, String districtAdcode,
        String longitude, String latitude,
        String category, String tagsJson,
        String rating, String cost, String ticketPrice, String suggestDuration,
        String openHours, String opentime2, String brief,
        String address, String tel, String photos,
        String officialAccount, String remark) {
}
