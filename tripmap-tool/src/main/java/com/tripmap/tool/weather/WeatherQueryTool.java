package com.tripmap.tool.weather;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripmap.common.exception.BizException;
import com.tripmap.common.result.ResultCode;
import com.tripmap.tool.spi.ToolNames;
import com.tripmap.tool.spi.TripTool;
import com.tripmap.tool.weather.client.QWeatherClient;
import com.tripmap.tool.weather.config.QWeatherProperties;
import com.tripmap.tool.weather.dto.DailyWeather;
import com.tripmap.tool.weather.dto.WeatherQueryInput;
import com.tripmap.tool.weather.dto.WeatherQueryOutput;
import com.tripmap.tool.weather.util.WeatherNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 天气查询工具（任务 2.3，设计说明书 §9 工具层）。
 *
 * <p><b>粒度</b>：城市级（TASKS 2.3.2「城市+日期区间」）。MVP 单城市杭州，
 * 坐标用 {@link QWeatherProperties#getCityCoordinateLat()}/{@code Lon}（GCJ-02）
 * 固定值，省去 geo 解析请求（2026-11 决策）；扩城市时再引入 {@code geo/v2/city}。</p>
 *
 * <p><b>流程</b>：参数校验（含城市白名单防御）→ 按预报窗口划分可预报/降级日 →
 * Redis 逐日缓存命中判断（key=city+date，TTL 30～60 分钟，TASKS 2.3.4）→
 * 未命中整段调用 {@code /weather/v1/daily}（一次覆盖，localTime=true）→
 * {@link WeatherNormalizer} 归一化 → 回写缓存 → 按输入区间组装输出。</p>
 *
 * <p><b>降级</b>（方案 A，TASKS 2.3.3）：日期超出预报窗口（offset ≥ forecastMaxDays）
 * 不调用 API，返回 {@code degraded=true} 的空预报，文案由攻略节点说明「临近再查」，
 * 模型不得编造天气（AGENTS.md 第 1 节原则 2）。</p>
 */
@Component
public class WeatherQueryTool implements TripTool<WeatherQueryInput, WeatherQueryOutput> {

    private static final Logger log = LoggerFactory.getLogger(WeatherQueryTool.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    /** Redis 缓存键前缀（key = prefix + city + ":" + yyyy-MM-dd） */
    private static final String CACHE_KEY_PREFIX = "tripmap:weather:";
    /** 合规归因（接口文档 §2.5：必须展示「和风天气 QWeather」） */
    private static final String ATTRIBUTION = "和风天气 QWeather";

    private final QWeatherClient client;
    private final StringRedisTemplate redisTemplate;
    private final QWeatherProperties properties;

    public WeatherQueryTool(QWeatherClient client, StringRedisTemplate redisTemplate,
                            QWeatherProperties properties) {
        this.client = client;
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    public String name() {
        return ToolNames.WEATHER_QUERY;
    }

    @Override
    public WeatherQueryOutput execute(WeatherQueryInput input) {
        validate(input);
        String city = input.getCity();
        LocalDate startDate = input.getStartDate();
        LocalDate today = LocalDate.now();

        List<LocalDate> tripDates = IntStream.range(0, input.getDays())
                .mapToObj(startDate::plusDays)
                .toList();

        // 1. 按预报窗口划分（offset ∈ [0, forecastMaxDays) 可真实预报）
        List<LocalDate> forecastable = new ArrayList<>();
        List<LocalDate> degradedDates = new ArrayList<>();
        for (LocalDate d : tripDates) {
            long offset = ChronoUnit.DAYS.between(today, d);
            if (offset >= 0 && offset < properties.getForecastMaxDays()) {
                forecastable.add(d);
            } else {
                degradedDates.add(d);
            }
        }
        log.info("天气查询: city={}, 区间={}~{}, 共 {} 天；今天={}, 可预报 {} 天, 降级 {} 天, "
                        + "预报窗口={}天/降级阈值={}天",
                city, startDate, startDate.plusDays(input.getDays() - 1), input.getDays(),
                today, forecastable.size(), degradedDates.size(),
                properties.getForecastMaxDays(), properties.getDegradeThreshold());

        // 2. 逐日缓存命中判断（key=city+date）
        Map<String, DailyWeather> byDate = new LinkedHashMap<>();
        List<LocalDate> missing = new ArrayList<>();
        int cacheHits = 0;
        for (LocalDate d : forecastable) {
            DailyWeather dw = readCache(city, d);
            if (dw != null) {
                byDate.put(dateStr(d), dw);
                cacheHits++;
            } else {
                missing.add(d);
            }
        }
        log.info("缓存命中 {} 天, 未命中 {} 天（key={}{}:{{date}}, TTL {} 分钟）",
                cacheHits, missing.size(), CACHE_KEY_PREFIX, city, properties.getCacheTtlMinutes());

        // 3. 有未命中 → 整段调用 API（一次覆盖今日至最远可预报日）
        if (!missing.isEmpty()) {
            long maxOffset = ChronoUnit.DAYS.between(today, forecastable.get(forecastable.size() - 1));
            int requestDays = (int) Math.min(properties.getForecastMaxDays(), maxOffset + 1);
            log.info("调用和风逐日预报: lat={}, lon={}, days={}",
                    properties.getCityCoordinateLat(), properties.getCityCoordinateLon(), requestDays);
            JsonNode root = client.getDailyForecast(properties.getCityCoordinateLat(),
                    properties.getCityCoordinateLon(), requestDays);
            List<DailyWeather> normalized = WeatherNormalizer.normalizeDaily(root);
            for (DailyWeather dw : normalized) {
                if (dw.getDate() != null) {
                    writeCache(city, dw);
                }
            }
            Map<String, DailyWeather> fetched = normalized.stream()
                    .filter(d -> d.getDate() != null)
                    .collect(Collectors.toMap(DailyWeather::getDate, d -> d, (a, b) -> a, LinkedHashMap::new));
            for (LocalDate d : forecastable) {
                byDate.put(dateStr(d), fetched.get(dateStr(d)));
            }
        }

        // 4. 按输入区间组装（缺失/降级日置 degraded=true）
        List<DailyWeather> result = new ArrayList<>();
        for (LocalDate d : tripDates) {
            DailyWeather dw = byDate.get(dateStr(d));
            result.add(dw != null ? dw : DailyWeather.builder().date(dateStr(d)).degraded(true).build());
        }
        boolean anyDegraded = result.stream().anyMatch(DailyWeather::isDegraded);
        printResult(city, result);
        return WeatherQueryOutput.builder()
                .city(city)
                .daily(result)
                .degraded(anyDegraded)
                .attribution(ATTRIBUTION)
                .build();
    }

    // ---------- 校验 ----------

    private void validate(WeatherQueryInput input) {
        if (input == null || input.getCity() == null || input.getCity().isBlank()) {
            throw new BizException(ResultCode.PARAM_MISSING, "缺少必填参数: city");
        }
        if (input.getStartDate() == null) {
            throw new BizException(ResultCode.PARAM_MISSING, "缺少必填参数: startDate");
        }
        if (input.getDays() < 1 || input.getDays() > 15) {
            throw new BizException(ResultCode.PARAM_VALIDATION_FAILED, "days 必须在 1～15 之间");
        }
        // 城市白名单防御（上游意图校验已挡，工具层仍自守）
        if (!properties.getDefaultCity().equals(input.getCity())) {
            throw new BizException(ResultCode.CITY_NOT_SUPPORTED);
        }
    }

    // ---------- Redis 缓存 ----------

    private String cacheKey(String city, LocalDate date) {
        return CACHE_KEY_PREFIX + city + ":" + dateStr(date);
    }

    private DailyWeather readCache(String city, LocalDate date) {
        String json = redisTemplate.opsForValue().get(cacheKey(city, date));
        if (json == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(json, DailyWeather.class);
        } catch (JsonProcessingException e) {
            log.warn("天气缓存反序列化失败，视为未命中: key={}", cacheKey(city, date), e);
            return null;
        }
    }

    private void writeCache(String city, DailyWeather dw) {
        try {
            String json = OBJECT_MAPPER.writeValueAsString(dw);
            redisTemplate.opsForValue().set(cacheKey(city, LocalDate.parse(dw.getDate())),
                    json, Duration.ofMinutes(properties.getCacheTtlMinutes()));
        } catch (Exception e) {
            // 缓存失败不影响主流程（天气数据仍可返回，仅下次多一次 API 调用）
            log.warn("天气缓存写入失败: city={}, date={}", city, dw.getDate(), e);
        }
    }

    // ---------- 日志 ----------

    private void printResult(String city, List<DailyWeather> daily) {
        StringBuilder sb = new StringBuilder("\n---------- 天气查询结果 city=").append(city).append(" ----------\n");
        sb.append(String.format("%-12s %-8s %-8s %-8s %-8s %-8s %-8s %s%n",
                "日期", "白天", "夜间", "最高°C", "最低°C", "降水概率%", "降水mm", "降级"));
        for (DailyWeather d : daily) {
            sb.append(String.format("%-12s %-8s %-8s %-8s %-8s %-8s %-8s %s%n",
                    d.getDate(), nvl(d.getDayCondition()), nvl(d.getNightCondition()),
                    nvl(d.getTempMaxC()), nvl(d.getTempMinC()), nvl(d.getPrecipProbabilityPct()),
                    nvl(d.getPrecipMm()), d.isDegraded() ? "是" : "否"));
        }
        log.info(sb.toString());
    }

    private static String nvl(Object o) {
        return o == null ? "-" : String.valueOf(o);
    }

    private static String dateStr(LocalDate d) {
        return d.format(DATE_FMT);
    }
}
