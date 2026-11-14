package com.tripmap.poi.importdb;

import com.tripmap.poi.excel.EnrichedPoiExcelRow;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 导入前映射+校验单测（功能设计说明书 §5.1 第③步，§4.6 防线 2）。
 *
 * <p>口径与功能二第⑤步对齐：open_hours 同一校验器、category 8 枚举、tags 词表闭集
 * （越界丢弃行仍导入）、ticket_price ≥ 0、suggest_duration ∈ (0, 720]；
 * 另覆盖坐标拆分与中国范围兜底、amap_id/name/cityname 必填、adcode 六位数字、
 * official_account 超长截断。</p>
 */
class PoiImportRowMapperTest {

    private EnrichedPoiExcelRow validRow() {
        EnrichedPoiExcelRow row = EnrichedPoiExcelRow.from(
                com.tripmap.poi.excel.RawPoiExcelRow.builder()
                        .amapId("B0FFFABC")
                        .name("花港观鱼")
                        .type("风景名胜;公园广场;公园")
                        .typecode("110101")
                        .cityname("杭州市")
                        .adname("西湖区")
                        .location("120.130000,30.210000")
                        .build());
        row.setEnrichStatus("SUCCESS");
        row.setDistrictAdcode("330106");
        row.setCategory("公园乐园");
        row.setSummary("西湖边以红鱼池闻名的公园");
        row.setOpenHours("{\"mon\":[{\"open\":\"09:00\",\"close\":\"17:00\"}],\"tue\":[],\"wed\":null,\"thu\":null,\"fri\":null,\"sat\":null,\"sun\":null}");
        row.setTags("亲子;湖景");
        row.setTicketPrice("0");
        row.setSuggestDuration("90");
        row.setOfficialAccount("杭州西湖风景名胜区");
        row.setRemark("以下字段由AI联网搜索整理，可能存在时效差异：景点概述、开放时间、标签");
        return row;
    }

    @Test
    void 合法行映射成功_字段归一() {
        PoiImportRowMapper.MappingResult result = PoiImportRowMapper.map(validRow());
        assertNull(result.error());
        assertNotNull(result.poi());
        assertEquals("B0FFFABC", result.poi().amapId());
        assertEquals("杭州市", result.poi().city());
        assertEquals("西湖区", result.poi().district());
        assertEquals("330106", result.poi().districtAdcode());
        assertEquals("120.130000", result.poi().longitude());  // 纯文本数值串（DECIMAL 列按标度存）
        assertEquals("30.210000", result.poi().latitude());
        assertEquals("[\"亲子\",\"湖景\"]", result.poi().tagsJson());
        assertEquals("0", result.poi().ticketPrice());
        assertEquals("90", result.poi().suggestDuration());
        assertEquals("西湖边以红鱼池闻名的公园", result.poi().brief()); // summary → brief
        assertTrue(result.poi().remark().startsWith("以下字段由AI"));
    }

    @Test
    void 增强字段全空仍可导入_openHours未知不阻塞() {
        EnrichedPoiExcelRow row = validRow();
        row.setCategory(null);
        row.setTags(null);
        row.setOpenHours(null);
        row.setSummary(null);
        row.setTicketPrice(null);
        row.setSuggestDuration(null);
        row.setDistrictAdcode(null);
        PoiImportRowMapper.MappingResult result = PoiImportRowMapper.map(row);
        assertNull(result.error());
        assertNull(result.poi().category());
        assertNull(result.poi().tagsJson());
        assertNull(result.poi().openHours());
    }

    @Test
    void amap_id为空_整行跳过() {
        EnrichedPoiExcelRow row = validRow();
        row.setAmapId("  ");
        PoiImportRowMapper.MappingResult result = PoiImportRowMapper.map(row);
        assertNotNull(result.error());
        assertTrue(result.error().contains("amap_id 为空"));
    }

    @Test
    void 坐标格式非法或越界_整行跳过() {
        EnrichedPoiExcelRow badFormat = validRow();
        badFormat.setLocation("120.13");
        assertNotNull(PoiImportRowMapper.map(badFormat).error());

        EnrichedPoiExcelRow swapped = validRow();
        swapped.setLocation("30.210000,120.130000"); // 经纬度错位，均落在中国范围校验外
        assertNotNull(PoiImportRowMapper.map(swapped).error());
    }

    @Test
    void openHours非法JSON_整行跳过() {
        EnrichedPoiExcelRow row = validRow();
        row.setOpenHours("{\"mon\":\"全天\"}");
        PoiImportRowMapper.MappingResult result = PoiImportRowMapper.map(row);
        assertNotNull(result.error());
        assertTrue(result.error().contains("open_hours 校验失败"));
    }

    @Test
    void category非空且非法_整行跳过_空category可导入() {
        EnrichedPoiExcelRow bad = validRow();
        bad.setCategory("绝美景点");
        PoiImportRowMapper.MappingResult result = PoiImportRowMapper.map(bad);
        assertNotNull(result.error());
        assertTrue(result.error().contains("category 非法"));
    }

    @Test
    void tags越界词丢弃并warn_清空后为NULL行仍导入() {
        EnrichedPoiExcelRow row = validRow();
        row.setTags("亲子;绝美;湖景");
        PoiImportRowMapper.MappingResult result = PoiImportRowMapper.map(row);
        assertNull(result.error());
        assertEquals("[\"亲子\",\"湖景\"]", result.poi().tagsJson());
        assertEquals(1, result.warnings().size());
        assertTrue(result.warnings().get(0).contains("绝美"));

        EnrichedPoiExcelRow allInvalid = validRow();
        allInvalid.setTags("绝美;必去");
        PoiImportRowMapper.MappingResult r2 = PoiImportRowMapper.map(allInvalid);
        assertNull(r2.error());
        assertNull(r2.poi().tagsJson());
        assertEquals(1, r2.warnings().size());
    }

    @Test
    void 数值校验_负门票_超时时长_非数字评分均跳过() {
        EnrichedPoiExcelRow negativePrice = validRow();
        negativePrice.setTicketPrice("-1");
        assertNotNull(PoiImportRowMapper.map(negativePrice).error());

        EnrichedPoiExcelRow zeroFree = validRow();
        zeroFree.setTicketPrice("0"); // 0=免费合法
        assertNull(PoiImportRowMapper.map(zeroFree).error());

        EnrichedPoiExcelRow tooLong = validRow();
        tooLong.setSuggestDuration("721");
        assertNotNull(PoiImportRowMapper.map(tooLong).error());

        EnrichedPoiExcelRow zeroDuration = validRow();
        zeroDuration.setSuggestDuration("0");
        assertNotNull(PoiImportRowMapper.map(zeroDuration).error());

        EnrichedPoiExcelRow badRating = validRow();
        badRating.setRating("五星");
        assertNotNull(PoiImportRowMapper.map(badRating).error());
    }

    @Test
    void adcode非六位数字_整行跳过() {
        EnrichedPoiExcelRow row = validRow();
        row.setDistrictAdcode("33010");
        assertNotNull(PoiImportRowMapper.map(row).error());
    }

    @Test
    void officialAccount超长截断并warn() {
        EnrichedPoiExcelRow row = validRow();
        row.setOfficialAccount("x".repeat(200));
        PoiImportRowMapper.MappingResult result = PoiImportRowMapper.map(row);
        assertNull(result.error());
        assertEquals(128, result.poi().officialAccount().length());
        assertEquals(1, result.warnings().size());
    }
}
