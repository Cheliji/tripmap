package com.tripmap.poi.collect;

import com.fasterxml.jackson.databind.JsonNode;
import com.tripmap.tool.amap.AmapClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 高德 POI 文本搜索分页拉取器（任务 1.1.3）
 *
 * <p>纯 POJO，零 Spring，基于 {@link AmapClient}（任务 1.1.2，自带 QPS 限流与退避重试），
 * 供采集脚本（1.3.2，{@code CollectMain}）复用。实现口径见《tripmap-poi功能设计说明书》§3.3：</p>
 *
 * <ul>
 *   <li>分页：{@code page} 从 1 递增，{@code page_size} 默认 25（高德上限）；
 *       <b>单查询最多 9 页</b>——实测第 10 页起返回空列表且 status=1 不报错，
 *       官方 {@code count} 字段远大于实际可获取量，不可作为翻页依据；</li>
 *   <li>类型检索一律传 {@code types} 分类编码（多值竖线分隔），响应 {@code typecode} 原样保留；</li>
 *   <li>字段口径见 {@link RawPoi}（§3.4，biz_ext 解析 rating/open_time/opentime2/level/cost）；</li>
 *   <li>空 {@code id} 的记录丢弃并记 warn（§3.3 去重前置）；</li>
 *   <li>翻页终止条件：返回空页、返回条数不足一页（最后一页）、或达到最大翻页深度——三者任一命中即停，
 *       <b>不依赖 count 字段</b>。</li>
 * </ul>
 *
 * <p>容错边界：单页查询失败由 {@link AmapClient} 抛 {@code ExternalApiException}，
 * 本类原样上抛；"单组合失败仅记日志跳过、不中断整体采集"的语义由采集脚本承担（§3.3）。</p>
 */
public class AmapPoiSearcher {

    private static final Logger log = LoggerFactory.getLogger(AmapPoiSearcher.class);

    private static final String PLACE_TEXT_PATH = "/v3/place/text";

    private final AmapClient amapClient;

    public AmapPoiSearcher(AmapClient amapClient) {
        this.amapClient = amapClient;
    }

    /**
     * 拉取一个（分区 × 分类）组合的全部结果：从第 1 页翻至空页/末页/最大深度（§3.3）。
     *
     * @param query 查询条件
     * @return 本组合全部 POI（含跨页），{@code amap_id} 全局唯一性由采集脚本去重保证
     */
    public List<RawPoi> search(AmapPoiSearchQuery query) {
        List<RawPoi> all = new ArrayList<>();
        for (int page = 1; page <= query.maxPages(); page++) {
            List<RawPoi> pageResult = searchPage(query, page);
            all.addAll(pageResult);
            log.debug("place/text 翻页: page={}/{}, 本页 {} 条, 累计 {} 条",
                    page, query.maxPages(), pageResult.size(), all.size());
            // 空页（实测第 10 页起的典型形态）或不足一页（最后一页）：后续不会再有数据
            if (pageResult.size() < query.pageSize()) {
                break;
            }
        }
        return all;
    }

    /**
     * 拉取单页（page 从 1 开始）。
     */
    public List<RawPoi> searchPage(AmapPoiSearchQuery query, int page) {
        Map<String, String> params = new LinkedHashMap<>();
        if (query.keywords() != null) {
            params.put("keywords", query.keywords());
        }
        if (query.types() != null) {
            params.put("types", query.types());
        }
        params.put("city", query.city());
        params.put("citylimit", String.valueOf(query.cityLimit()));
        if (query.region() != null) {
            params.put("region", query.region());
        }
        params.put("page", String.valueOf(page));
        params.put("offset", String.valueOf(query.pageSize()));

        JsonNode root = amapClient.get(PLACE_TEXT_PATH, params);
        JsonNode pois = root.path("pois");
        if (!pois.isArray()) {
            return List.of();
        }
        List<RawPoi> result = new ArrayList<>(pois.size());
        for (JsonNode poi : pois) {
            RawPoi raw = parse(poi);
            if (!StringUtils.hasText(raw.getAmapId())) {
                // §3.3：空 id 的记录丢弃并记 warn（无法参与 amap_id 去重与后续 upsert）
                log.warn("丢弃空 id 的 POI 记录: name={}, address={}", raw.getName(), raw.getAddress());
                continue;
            }
            result.add(raw);
        }
        return result;
    }

    /**
     * 高德响应单条 POI → {@link RawPoi}，字段口径见 §3.4（adcode/pcode 响应中不存在不采集）。
     */
    private RawPoi parse(JsonNode poi) {
        RawPoi raw = new RawPoi();
        raw.setAmapId(textOrNull(poi.path("id")));
        raw.setName(textOrNull(poi.path("name")));
        raw.setType(textOrNull(poi.path("type")));
        raw.setTypecode(textOrNull(poi.path("typecode")));
        raw.setKeytag(textOrNull(poi.path("keytag")));
        raw.setTel(textOrNull(poi.path("tel")));
        raw.setAddress(textOrNull(poi.path("address")));
        raw.setLocation(textOrNull(poi.path("location")));
        raw.setPname(textOrNull(poi.path("pname")));
        raw.setCityname(textOrNull(poi.path("cityname")));
        raw.setAdname(textOrNull(poi.path("adname")));

        // biz_ext：实测携带 rating/open_time/opentime2/level/cost（§3.4）
        JsonNode bizExt = poi.path("biz_ext");
        raw.setLevel(textOrNull(bizExt.path("level")));
        raw.setRating(textOrNull(bizExt.path("rating")));
        raw.setOpenTime(textOrNull(bizExt.path("open_time")));
        raw.setOpentime2(textOrNull(bizExt.path("opentime2")));
        raw.setCost(textOrNull(bizExt.path("cost")));

        raw.setPhotos(joinPhotos(poi.path("photos")));
        return raw;
    }

    /** photos[].url 分号拼接（§3.4）；无 photo 节点时返回 null */
    private String joinPhotos(JsonNode photos) {
        if (!photos.isArray() || photos.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode photo : photos) {
            String url = textOrNull(photo.path("url"));
            if (url == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(url);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /** 缺失节点返回 null（而非 asText(null) 对 NullNode 返回 "null" 字符串的歧义） */
    private static String textOrNull(JsonNode node) {
        return (node == null || node.isMissingNode() || node.isNull()) ? null : node.asText();
    }
}
