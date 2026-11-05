package com.tripmap.poi.collect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tripmap.tool.amap.AmapClient;
import com.tripmap.tool.amap.AmapProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 高德 POI 文本搜索分页拉取器单测（任务 1.1.3）
 *
 * <p>MockWebServer 桩验证：查询参数拼装（types/city/citylimit/region）、
 * biz_ext 字段解析（§3.4）、photos 拼接、翻页终止三条件
 * （空页/不足一页/最大深度）、空 id 丢弃、查询条件校验。</p>
 */
class AmapPoiSearcherTest {

    private MockWebServer server;
    private AmapPoiSearcher searcher;
    private final com.fasterxml.jackson.databind.ObjectMapper om =
            new com.fasterxml.jackson.databind.ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        AmapProperties properties = new AmapProperties();
        properties.setApiKey("test-key-123");
        properties.setBaseUrl(server.url("/").toString().replaceAll("/$", ""));
        properties.setQps(1000); // 单测内不限速
        properties.setMaxRetries(0);
        searcher = new AmapPoiSearcher(new AmapClient(AmapClient.buildRestClient(properties), properties));
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    /** 构造一条 place/text 响应：pois 数组 + count 干扰字段（验证不依赖 count 翻页） */
    private MockResponse poisResponse(List<? extends JsonNode> pois) {
        ObjectNode root = om.createObjectNode();
        root.put("status", "1");
        root.put("count", "999"); // 故意远大于实际可获取量：翻页不得依据 count（§3.3）
        root.put("info", "OK");
        ArrayNode arr = root.putArray("pois");
        pois.forEach(arr::add);
        return new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody(root.toString());
    }

    private ObjectNode poi(String id, String name) {
        ObjectNode poi = om.createObjectNode();
        poi.put("id", id);
        poi.put("name", name);
        poi.put("type", "风景名胜;公园广场;公园");
        poi.put("typecode", "110101");
        poi.put("keytag", "5A景区");
        poi.put("tel", "0571-123456");
        poi.put("address", "杭州市西湖区示例路1号");
        poi.put("location", "120.123456,30.123456");
        poi.put("pname", "浙江省");
        poi.put("cityname", "杭州市");
        poi.put("adname", "西湖区");
        return poi;
    }

    private ObjectNode poiWithBizExt() {
        ObjectNode poi = poi("B000A1", "示例博物馆");
        ObjectNode bizExt = poi.putObject("biz_ext");
        bizExt.put("rating", "4.7");
        bizExt.put("level", "AAAA");
        bizExt.put("open_time", "09:00-17:00");
        bizExt.put("opentime2", "周一 闭馆(法定节假日除外)；周二至周日 09:00-16:30");
        bizExt.put("cost", "30");
        ArrayNode photos = poi.putArray("photos");
        photos.addObject().put("url", "http://img1.example/a.jpg");
        photos.addObject().put("url", "http://img2.example/b.jpg");
        return poi;
    }

    @Test
    void 查询参数拼装正确且关键词缺省不传() throws Exception {
        server.enqueue(poisResponse(List.of(poi("B000A1", "公园A"))));

        AmapPoiSearchQuery query = AmapPoiSearchQuery.byTypes("110101|110102", "杭州", "330106");
        List<RawPoi> result = searcher.search(query);

        assertEquals(1, result.size());
        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        String path = request.getPath();
        assertTrue(path.contains("types=110101%7C110102"), "types 多值应竖线分隔并编码: " + path);
        assertTrue(path.contains("city=%E6%9D%AD%E5%B7%9E"), "城市应编码: " + path);
        assertTrue(path.contains("citylimit=true"), "citylimit 应限定本市: " + path);
        assertTrue(path.contains("region=330106"), "分区 adcode 应传入: " + path);
        assertTrue(path.contains("page=1"), "应从第 1 页开始: " + path);
        assertTrue(path.contains("offset=25"), "page_size 默认 25: " + path);
        assertTrue(!path.contains("keywords="), "keywords 缺省不应出现在请求中: " + path);
    }

    @Test
    void bizExt与photos按设计说明书列定义解析() {
        server.enqueue(poisResponse(List.of(poiWithBizExt())));

        List<RawPoi> result = searcher.search(AmapPoiSearchQuery.byTypes("140100", "杭州", null));

        assertEquals(1, result.size());
        RawPoi raw = result.get(0);
        assertEquals("B000A1", raw.getAmapId());
        assertEquals("示例博物馆", raw.getName());
        assertEquals("风景名胜;公园广场;公园", raw.getType());
        assertEquals("110101", raw.getTypecode());
        assertEquals("5A景区", raw.getKeytag());
        assertEquals("AAAA", raw.getLevel());
        assertEquals("4.7", raw.getRating());
        assertEquals("09:00-17:00", raw.getOpenTime());
        assertEquals("周一 闭馆(法定节假日除外)；周二至周日 09:00-16:30", raw.getOpentime2());
        assertEquals("30", raw.getCost());
        assertEquals("120.123456,30.123456", raw.getLocation());
        assertEquals("西湖区", raw.getAdname());
        assertEquals("http://img1.example/a.jpg;http://img2.example/b.jpg", raw.getPhotos());
    }

    @Test
    void 翻页至不足一页终止且不依赖count() throws Exception {
        // 第 1 页满 25 条，第 2 页 3 条（最后一页）：应恰好翻 2 页
        server.enqueue(poisResponse(java.util.stream.IntStream.rangeClosed(1, 25)
                .mapToObj(i -> poi("ID-" + i, "景点" + i)).toList()));
        server.enqueue(poisResponse(java.util.stream.IntStream.rangeClosed(26, 28)
                .mapToObj(i -> poi("ID-" + i, "景点" + i)).toList()));

        List<RawPoi> result = searcher.search(AmapPoiSearchQuery.byTypes("110000", "杭州", null));

        assertEquals(28, result.size());
        assertEquals(2, server.getRequestCount(), "不足一页即最后一页，应停止翻页");
    }

    @Test
    void 空页提前终止翻页() throws Exception {
        // 第 1 页满 25 条，第 2 页空列表（实测第 10 页起的典型形态：空列表且 status=1）
        server.enqueue(poisResponse(java.util.stream.IntStream.rangeClosed(1, 25)
                .mapToObj(i -> poi("ID-" + i, "景点" + i)).toList()));
        server.enqueue(poisResponse(List.of()));

        List<RawPoi> result = searcher.search(AmapPoiSearchQuery.byTypes("110000", "杭州", null));

        assertEquals(25, result.size());
        assertEquals(2, server.getRequestCount(), "空页即终止，不应继续深翻");
    }

    @Test
    void 达到最大翻页深度即停止() throws Exception {
        // maxPages=2 且两页均满：即使 count 暗示还有数据也必须停（§3.3）
        AmapPoiSearchQuery query = new AmapPoiSearchQuery(null, "110000", "杭州", true, null, 25, 2);
        server.enqueue(poisResponse(java.util.stream.IntStream.rangeClosed(1, 25)
                .mapToObj(i -> poi("ID-" + i, "景点" + i)).toList()));
        server.enqueue(poisResponse(java.util.stream.IntStream.rangeClosed(26, 50)
                .mapToObj(i -> poi("ID-" + i, "景点" + i)).toList()));

        List<RawPoi> result = searcher.search(query);

        assertEquals(50, result.size());
        assertEquals(2, server.getRequestCount(), "达到 maxPages 应立即停止，count=999 不得作为翻页依据");
    }

    @Test
    void 空id记录丢弃() {
        ObjectNode blankId = poi(" ", "无名景点");
        server.enqueue(poisResponse(List.of(poi("B000A1", "正常景点"), blankId)));

        List<RawPoi> result = searcher.search(AmapPoiSearchQuery.byTypes("110000", "杭州", null));

        assertEquals(1, result.size());
        assertEquals("B000A1", result.get(0).getAmapId());
    }

    @Test
    void 查询条件校验() {
        // keywords 与 types 同时为空：非法
        assertThrows(IllegalArgumentException.class,
                () -> new AmapPoiSearchQuery(null, null, "杭州", true, null, 25, 9));
        // city 为空：非法
        assertThrows(IllegalArgumentException.class,
                () -> new AmapPoiSearchQuery("西湖", null, " ", true, null, 25, 9));
        // pageSize 超高德上限：非法
        assertThrows(IllegalArgumentException.class,
                () -> new AmapPoiSearchQuery(null, "110000", "杭州", true, null, 26, 9));
        // maxPages < 1：非法
        assertThrows(IllegalArgumentException.class,
                () -> new AmapPoiSearchQuery(null, "110000", "杭州", true, null, 25, 0));
        // keywords 单用合法（补充手段，§3.2）
        AmapPoiSearchQuery byKeywords = new AmapPoiSearchQuery("西湖", null, "杭州", true, null, 10, 5);
        assertEquals(10, byKeywords.pageSize());
        assertNull(byKeywords.types());
    }
}
