package com.tripmap.tool.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripmap.common.exception.BizException;
import com.tripmap.tool.weather.client.QWeatherClient;
import com.tripmap.tool.weather.config.QWeatherProperties;
import com.tripmap.tool.weather.dto.DailyWeather;
import com.tripmap.tool.weather.dto.WeatherQueryInput;
import com.tripmap.tool.weather.dto.WeatherQueryOutput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 天气查询工具单测（任务 2.3.2/2.3.3/2.3.4）。
 *
 * <p>QWeatherClient 与 StringRedisTemplate 走 Mockito 桩（无真实网络/Redis），
 * 覆盖：参数校验、城市白名单、预报窗口划分与降级、Redis 缓存命中/未命中。</p>
 *
 * <p>按 AGENTS.md 定规：每个用例打印场景、输入与输出表，console 可直接人工 review。</p>
 */
class WeatherQueryToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    private final QWeatherClient client = mock(QWeatherClient.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final QWeatherProperties properties = new QWeatherProperties();
    private final WeatherQueryTool tool = new WeatherQueryTool(client, redis, properties);

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
    }

    // ---------- 辅助 ----------

    private String dateStr(LocalDate d) {
        return d.format(DATE_FMT);
    }

    private String cacheKey(LocalDate d) {
        return "tripmap:weather:杭州:" + dateStr(d);
    }

    /** 构造逐日预报响应：from 起 count 天 */
    private JsonNode dailyResponse(LocalDate from, int count) throws Exception {
        StringBuilder sb = new StringBuilder("{\"days\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(',');
            }
            LocalDate d = from.plusDays(i);
            sb.append("{\"forecastStartTime\":\"").append(dateStr(d)).append("T06:00+08:00\",")
                    .append("\"temperatureMax\":{\"value\":20.0},\"temperatureMin\":{\"value\":10.0},")
                    .append("\"daytime\":{\"condition\":{\"text\":\"多云\"},")
                    .append("\"precipitation\":{\"amount\":{\"value\":0.0},\"probability\":0.2}},")
                    .append("\"nighttime\":{\"condition\":{\"text\":\"晴\"}}}");
        }
        sb.append("]}");
        return MAPPER.readTree(sb.toString());
    }

    private void printOutput(String scenario, WeatherQueryInput input, WeatherQueryOutput out) {
        System.out.println("\n========== 场景：" + scenario + " ==========");
        System.out.printf("  输入: city=%s, startDate=%s, days=%d（今天=%s）%n",
                input.getCity(), input.getStartDate(), input.getDays(), LocalDate.now());
        System.out.printf("  整体降级=%s, 归因=%s%n", out.isDegraded(), out.getAttribution());
        System.out.println("  " + String.format("%-12s %-6s %-6s %-8s %-8s %-8s %-8s %s",
                "日期", "白天", "夜间", "最高°C", "最低°C", "降水概率%", "降水mm", "降级"));
        for (DailyWeather d : out.getDaily()) {
            System.out.println("  " + String.format("%-12s %-6s %-6s %-8s %-8s %-8s %-8s %s",
                    d.getDate(), nvl(d.getDayCondition()), nvl(d.getNightCondition()),
                    nvl(d.getTempMaxC()), nvl(d.getTempMinC()),
                    nvl(d.getPrecipProbabilityPct()), nvl(d.getPrecipMm()), d.isDegraded() ? "是" : "否"));
        }
    }

    private static String nvl(Object o) {
        return o == null ? "-" : String.valueOf(o);
    }

    // ---------- 参数校验 ----------

    @Test
    void city为空抛BizException() {
        BizException e = assertThrows(BizException.class,
                () -> tool.execute(WeatherQueryInput.builder().city(" ").startDate(LocalDate.now()).days(1).build()));
        System.out.println("\n========== 场景：city 为空 → BizException(100002) ==========");
        System.out.printf("  实际抛出: code=%d, message=%s%n", e.getCode(), e.getMessage());
        assertEquals(100002, e.getCode());
    }

    @Test
    void days越界抛BizException() {
        BizException e = assertThrows(BizException.class,
                () -> tool.execute(WeatherQueryInput.builder().city("杭州").startDate(LocalDate.now()).days(16).build()));
        System.out.println("\n========== 场景：days=16 越界 → BizException(100001) ==========");
        System.out.printf("  实际抛出: code=%d, message=%s%n", e.getCode(), e.getMessage());
        assertEquals(100001, e.getCode());
    }

    @Test
    void 非杭州城市抛城市不支持() {
        BizException e = assertThrows(BizException.class,
                () -> tool.execute(WeatherQueryInput.builder().city("北京").startDate(LocalDate.now()).days(1).build()));
        System.out.println("\n========== 场景：city=北京 → BizException(300001 城市不支持) ==========");
        System.out.printf("  实际抛出: code=%d, message=%s%n", e.getCode(), e.getMessage());
        assertEquals(300001, e.getCode());
    }

    // ---------- 降级（方案 A） ----------

    @Test
    void 超过预报窗口全降级且不调用API() {
        LocalDate start = LocalDate.now().plusDays(10); // offset=10 ≥ forecastMaxDays(10)
        WeatherQueryInput input = WeatherQueryInput.builder().city("杭州").startDate(start).days(1).build();

        WeatherQueryOutput out = tool.execute(input);
        printOutput("超窗口全降级（start=today+10）", input, out);

        assertTrue(out.isDegraded(), "整体应降级");
        assertEquals(1, out.getDaily().size());
        assertTrue(out.getDaily().get(0).isDegraded(), "该日应降级");
        assertNull(out.getDaily().get(0).getDayCondition(), "降级日无真实天气");
        verify(client, never()).getDailyForecast(anyDouble(), anyDouble(), anyInt());
        verify(valueOps, never()).get(anyString());
    }

    @Test
    void 部分降级仅调用API覆盖可预报段() throws Exception {
        LocalDate start = LocalDate.now().plusDays(9); // offset=9 可预报，offset=10/11 降级
        WeatherQueryInput input = WeatherQueryInput.builder().city("杭州").startDate(start).days(3).build();

        // 请求天数 = maxOffset(9) + 1 = 10，返回今天起 10 天
        when(client.getDailyForecast(30.25, 120.15, 10)).thenReturn(dailyResponse(LocalDate.now(), 10));

        WeatherQueryOutput out = tool.execute(input);
        printOutput("部分降级（start=today+9, 3 天：可预报1天+降级2天）", input, out);

        verify(client, times(1)).getDailyForecast(30.25, 120.15, 10);
        assertEquals(3, out.getDaily().size());
        assertFalse(out.getDaily().get(0).isDegraded(), "第 1 天（offset 9）应有真实预报");
        assertTrue(out.getDaily().get(1).isDegraded(), "第 2 天（offset 10）应降级");
        assertTrue(out.getDaily().get(2).isDegraded(), "第 3 天（offset 11）应降级");
        assertTrue(out.isDegraded(), "任一天降级则整体降级");
    }

    // ---------- Redis 缓存（2.3.4） ----------

    @Test
    void 缓存命中不调用API() throws Exception {
        LocalDate start = LocalDate.now();
        WeatherQueryInput input = WeatherQueryInput.builder().city("杭州").startDate(start).days(1).build();

        DailyWeather cached = DailyWeather.builder()
                .date(dateStr(start)).dayCondition("阴").nightCondition("小雨")
                .tempMaxC(19.0).tempMinC(11.0).precipProbabilityPct(60.0).precipMm(1.2)
                .uvIndexMax(3).degraded(false).build();
        when(valueOps.get(cacheKey(start))).thenReturn(MAPPER.writeValueAsString(cached));

        WeatherQueryOutput out = tool.execute(input);
        printOutput("缓存命中（start=today, 1 天）", input, out);

        verify(client, never()).getDailyForecast(anyDouble(), anyDouble(), anyInt());
        assertEquals("阴", out.getDaily().get(0).getDayCondition());
        assertEquals(60.0, out.getDaily().get(0).getPrecipProbabilityPct());
        assertFalse(out.isDegraded());
    }

    @Test
    void 缓存未命中调用API并回写缓存() throws Exception {
        LocalDate start = LocalDate.now();
        WeatherQueryInput input = WeatherQueryInput.builder().city("杭州").startDate(start).days(2).build();

        when(client.getDailyForecast(30.25, 120.15, 2)).thenReturn(dailyResponse(LocalDate.now(), 2));

        WeatherQueryOutput out = tool.execute(input);
        printOutput("缓存未命中（start=today, 2 天）", input, out);

        verify(client, times(1)).getDailyForecast(30.25, 120.15, 2);
        verify(valueOps, times(2)).set(anyString(), anyString(), any());
        assertEquals(2, out.getDaily().size());
        assertEquals("多云", out.getDaily().get(0).getDayCondition());
        assertEquals(20.0, out.getDaily().get(0).getPrecipProbabilityPct(), "0.2 概率应 ×100 = 20%");
        assertFalse(out.isDegraded());
    }
}
