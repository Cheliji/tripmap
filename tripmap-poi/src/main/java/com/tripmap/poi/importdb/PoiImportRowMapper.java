package com.tripmap.poi.importdb;

import com.tripmap.common.constant.PoiCategory;
import com.tripmap.common.constant.PoiTagVocab;
import com.tripmap.poi.excel.EnrichedPoiExcelRow;
import com.tripmap.tool.openhours.OpenHoursValidator;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * enriched Excel 行 → {@link ImportablePoi} 的映射 + 导入前校验（功能设计说明书 §5.1 第③步，
 * §4.6 三道防线之防线 2：与功能二共用 {@link OpenHoursValidator}，防规则漂移）。
 *
 * <p>校验失败（{@link MappingResult#error()} 非空）→ 整行跳过，原因进入跳过清单；
 * 可修复问题（tags 越界词、official_account 超长）→ 修复 + warning，行仍导入（§4.5）。</p>
 *
 * <p>口径与功能二第⑤步对齐（§4.2/§5.1）：category 空=未映射可导入、非空必须 ∈ 8 枚举中文标签；
 * ticket_price ≥ 0（0=免费须有素材依据）；suggest_duration ∈ (0, 720]；数值列必须可解析
 * （防手改 Excel 引入脏数据）；坐标拆分后做中国范围兜底（防经纬度列错位）。</p>
 */
final class PoiImportRowMapper {

    /** suggest_duration 合法区间 (0, 720]（§5.1，与 RowEnricher 第⑤步同一口径） */
    private static final BigDecimal DURATION_MIN = BigDecimal.ZERO;
    private static final BigDecimal DURATION_MAX = new BigDecimal(720);
    /** district_adcode：GB/T 2260 六位数字（§4.3） */
    private static final Pattern ADCODE = Pattern.compile("\\d{6}");
    /** official_account 截断长度（poi 表 VARCHAR(128)，§5.1） */
    private static final int OFFICIAL_ACCOUNT_MAX = 128;

    private PoiImportRowMapper() {
    }

    /**
     * 映射结果三元组：{@code error != null} 表示整行不可导入（跳过）；
     * {@code warnings} 为可修复问题的修复说明（调用方负责记 warn 日志）。
     */
    record MappingResult(ImportablePoi poi, String error, List<String> warnings) {
    }

    /**
     * 校验并映射一行为 {@link ImportablePoi}。
     *
     * @param row Excel 行（已确认为 enrich_status=SUCCESS）
     * @return error 为 null 表示可导入；poi 在 error 非 null 时为 null
     */
    static MappingResult map(EnrichedPoiExcelRow row) {
        List<String> warnings = new ArrayList<>();
        String id = row.getAmapId();

        // ---- 必填与坐标（§5.1：amap_id 为空整行跳过；location 拆 "lng,lat"）----
        String error = requireText(id, "amap_id 为空");
        if (error == null) {
            error = requireText(row.getName(), "name 为空");
        }
        if (error == null) {
            error = requireText(row.getCityname(), "cityname 为空");
        }
        String[] lngLat = null;
        if (error == null) {
            lngLat = parseLocation(row.getLocation());
            if (lngLat == null) {
                error = "location 坐标缺失或格式非法（应为 \"lng,lat\"）: " + row.getLocation();
            }
        }
        if (error != null) {
            return new MappingResult(null, id + ": " + error, warnings);
        }

        // ---- open_hours（防线 2：同一校验器）----
        String openHours = null;
        if (StringUtils.hasText(row.getOpenHours())) {
            String ohError = OpenHoursValidator.validate(row.getOpenHours());
            if (ohError != null) {
                return new MappingResult(null, id + ": open_hours 校验失败: " + ohError, warnings);
            }
            openHours = row.getOpenHours();
        }

        // ---- category：空=未映射可导入（§4.2）；非空必须 ∈ 8 枚举 ----
        String category = trimToNull(row.getCategory());
        if (category != null && !PoiCategory.labels().contains(category)) {
            return new MappingResult(null,
                    id + ": category 非法（应为 8 枚举中文标签之一）: " + category, warnings);
        }

        // ---- tags：越界词丢弃 + warn，清空后 tags=NULL，行仍导入（§4.5）----
        String tagsJson = null;
        if (StringUtils.hasText(row.getTags())) {
            List<String> kept = new ArrayList<>();
            List<String> dropped = new ArrayList<>();
            for (String tag : row.getTags().split(";")) {
                String t = tag.trim();
                if (t.isEmpty()) {
                    continue;
                }
                if (PoiTagVocab.isValid(t)) {
                    kept.add(t);
                } else {
                    dropped.add(t);
                }
            }
            if (!dropped.isEmpty()) {
                warnings.add(id + ": tags 越界已丢弃: " + String.join(";", dropped) + "（§4.5）");
            }
            if (!kept.isEmpty()) {
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < kept.size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append('"').append(kept.get(i)).append('"');
                }
                tagsJson = sb.append(']').toString();
            }
        }

        // ---- 数值列：可解析 + 区间（§5.1）----
        BigDecimal rating = parseDecimal(row.getRating());
        if (row.getRating() != null && rating == null) {
            return reject(id, warnings, "rating 不是数字: " + row.getRating());
        }
        BigDecimal cost = parseDecimal(row.getCost());
        if (row.getCost() != null && cost == null) {
            return reject(id, warnings, "cost 不是数字: " + row.getCost());
        }
        BigDecimal ticketPrice = parseDecimal(row.getTicketPrice());
        if (row.getTicketPrice() != null) {
            if (ticketPrice == null) {
                return reject(id, warnings, "ticket_price 不是数字: " + row.getTicketPrice());
            }
            if (ticketPrice.compareTo(BigDecimal.ZERO) < 0) {
                return reject(id, warnings, "ticket_price 不允许为负: " + row.getTicketPrice());
            }
        }
        BigDecimal duration = parseDecimal(row.getSuggestDuration());
        if (row.getSuggestDuration() != null) {
            if (duration == null || duration.stripTrailingZeros().scale() > 0) {
                return reject(id, warnings, "suggest_duration 不是整数（分钟）: " + row.getSuggestDuration());
            }
            if (duration.compareTo(DURATION_MIN) <= 0 || duration.compareTo(DURATION_MAX) > 0) {
                return reject(id, warnings, "suggest_duration 超出合法区间 (0, 720]: " + row.getSuggestDuration());
            }
        }

        // ---- district_adcode：可空；非空须 6 位数字（§4.3）----
        String adcode = trimToNull(row.getDistrictAdcode());
        if (adcode != null && !ADCODE.matcher(adcode).matches()) {
            return reject(id, warnings, "district_adcode 不是 6 位数字: " + row.getDistrictAdcode());
        }

        // ---- official_account 超长截断（§5.1）----
        String officialAccount = trimToNull(row.getOfficialAccount());
        if (officialAccount != null && officialAccount.length() > OFFICIAL_ACCOUNT_MAX) {
            warnings.add(id + ": official_account 超长已截断至 " + OFFICIAL_ACCOUNT_MAX + " 字符");
            officialAccount = officialAccount.substring(0, OFFICIAL_ACCOUNT_MAX);
        }

        return new MappingResult(new ImportablePoi(
                id.trim(), row.getName().trim(),
                trimToNull(row.getType()), trimToNull(row.getTypecode()),
                trimToNull(row.getKeytag()), trimToNull(row.getLevel()),
                row.getCityname().trim(), trimToNull(row.getAdname()), adcode,
                lngLat[0], lngLat[1],
                category, tagsJson,
                plain(rating), plain(cost), plain(ticketPrice), plain(duration),
                openHours, trimToNull(row.getOpentime2()), trimToNull(row.getSummary()),
                trimToNull(row.getAddress()), trimToNull(row.getTel()), trimToNull(row.getPhotos()),
                officialAccount, trimToNull(row.getRemark())),
                null, warnings);
    }

    /**
     * 解析高德 location "lng,lat"（§3.4）为两个纯文本数值串；
     * 解析失败或超出中国范围（防手改错位）返回 null。
     */
    private static String[] parseLocation(String location) {
        if (!StringUtils.hasText(location) || !location.contains(",")) {
            return null;
        }
        String[] parts = location.split(",", -1);
        if (parts.length != 2) {
            return null;
        }
        BigDecimal lng;
        BigDecimal lat;
        try {
            lng = new BigDecimal(parts[0].trim());
            lat = new BigDecimal(parts[1].trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (lng.compareTo(new BigDecimal("73")) <= 0 || lng.compareTo(new BigDecimal("136")) >= 0
                || lat.compareTo(new BigDecimal("3")) <= 0 || lat.compareTo(new BigDecimal("54")) >= 0) {
            return null; // 超出中国范围，疑似经纬度错位/脏数据
        }
        return new String[]{lng.toPlainString(), lat.toPlainString()};
    }

    private static MappingResult reject(String id, List<String> warnings, String error) {
        return new MappingResult(null, id + ": " + error, warnings);
    }

    private static String requireText(String value, String error) {
        return StringUtils.hasText(value) ? null : error;
    }

    private static BigDecimal parseDecimal(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }
}
