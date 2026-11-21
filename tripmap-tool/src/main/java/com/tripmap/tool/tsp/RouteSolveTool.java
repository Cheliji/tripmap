package com.tripmap.tool.tsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripmap.common.exception.BizException;
import com.tripmap.common.result.ResultCode;
import com.tripmap.dal.entity.PoiEntity;
import com.tripmap.dal.mapper.PoiMapper;
import com.tripmap.tool.spi.ToolNames;
import com.tripmap.tool.spi.TripTool;
import com.tripmap.tool.tsp.config.RouteSolveProperties;
import com.tripmap.tool.tsp.dto.RouteSolveInput;
import com.tripmap.tool.tsp.dto.RouteSolveOutput;
import com.tripmap.tool.tsp.dto.RouteStop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 日内路径求解工具（任务 2.6，设计说明书 §6.3 双开放最短路径 + 时间窗 + 午餐禁游）。
 *
 * <p><b>流程</b>：参数校验 → 通勤矩阵维度校验 → 时间参数解析（input 优先、
 * 缺省回落配置）→ ID 批量查库取 open_hours / suggest_duration（DB 为事实源，
 * 同 {@code CommuteMatrixTool.loadPois} 模式）→ 按当日星期解析开放时段 →
 * 纯 Java 全排列精确枚举（{@link RouteEnumerator}）→ 无解抛 300002 → 装配
 * 逐站明细输出。</p>
 *
 * <p><b>默认值口径</b>（2026-11 对齐结论）：</p>
 * <ul>
 *   <li>open_hours 整列 null / 某天键 null → 按 24h 开放（三态语义"未知不参与硬约束"）；
 *       某天 = [] → 确定闭馆，含该点的任意排列直接剪枝；</li>
 *   <li>suggest_duration null → input.defaultVisitMinutes（LLM 传入），
 *       再 null → 配置默认 90 分钟；</li>
 *   <li>午餐禁游 [lunchStart, lunchEnd) 由 input 传入，null → 配置 11:30~14:00，
 *       首末相等 = 禁用。</li>
 * </ul>
 *
 * <p>各天相互独立、可并行调用（编排层 3.6 逐簇调本工具），单日失败（300002）
 * 不影响其他日（任务 2.6.5）。</p>
 */
@Component
public class RouteSolveTool implements TripTool<RouteSolveInput, RouteSolveOutput> {

    private static final Logger log = LoggerFactory.getLogger(RouteSolveTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** HH:mm（HH 00~23） */
    private static final Pattern HH_MM = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d");
    /** close 允许 24:00（= 当日 1440 分钟结束，24h 开放场所通用表达） */
    private static final String END_OF_DAY = "24:00";
    private static final int MINUTES_PER_DAY = 24 * 60;

    private final PoiMapper poiMapper;
    private final RouteSolveProperties properties;

    public RouteSolveTool(PoiMapper poiMapper, RouteSolveProperties properties) {
        this.poiMapper = poiMapper;
        this.properties = properties;
    }

    @Override
    public String name() {
        return ToolNames.ROUTE_SOLVE;
    }

    @Override
    public RouteSolveOutput execute(RouteSolveInput input) {
        // 1. 参数校验 + ID 去重保序
        List<Long> poiIds = validateAndDistinct(input);
        int n = poiIds.size();
        if (n > properties.getMaxPoints()) {
            throw new BizException(ResultCode.PARAM_VALIDATION_FAILED,
                    "单日景点数超过枚举护栏(" + properties.getMaxPoints() + "): " + n);
        }

        // 2. 矩阵维度校验（-1 = 缺失，交给枚举剪枝）
        long[][] matrix = validateMatrix(input.getDurationsSeconds(), n);

        // 3. 时间参数解析（秒）：input 优先，缺省回落配置
        LocalTime startTime = input.getStartTime() != null
                ? input.getStartTime() : properties.getDefaultStartTime();
        LocalTime lunchStart = input.getLunchStart() != null
                ? input.getLunchStart() : properties.getDefaultLunchStart();
        LocalTime lunchEnd = input.getLunchEnd() != null
                ? input.getLunchEnd() : properties.getDefaultLunchEnd();
        if (lunchStart.isAfter(lunchEnd)) {
            throw new BizException(ResultCode.PARAM_ILLEGAL, "午餐禁游开始不得晚于结束: " + lunchStart + " > " + lunchEnd);
        }
        int defaultVisitMinutes = input.getDefaultVisitMinutes() != null
                ? input.getDefaultVisitMinutes() : properties.getDefaultVisitMinutes();
        if (defaultVisitMinutes <= 0) {
            throw new BizException(ResultCode.PARAM_ILLEGAL, "默认游玩时长必须为正数: " + defaultVisitMinutes);
        }
        int startSec = toSecondOfDay(startTime);
        int lunchStartSec = toSecondOfDay(lunchStart);
        int lunchEndSec = toSecondOfDay(lunchEnd);

        // 4. 查库取 open_hours / suggest_duration，并按当日星期解析开放时段
        List<PoiEntity> pois = loadPois(poiIds);
        DayOfWeek dow = input.getDate().getDayOfWeek();
        int[] visitSec = new int[n];
        int[][] openSecSeconds = new int[n][];
        boolean[] defaultDuration = new boolean[n];
        boolean[] defaultOpen = new boolean[n];
        List<Long> closedPoiIds = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            PoiEntity poi = pois.get(i);
            boolean durationMissing = poi.getSuggestDuration() == null;
            defaultDuration[i] = durationMissing;
            int visitMinutes = durationMissing ? defaultVisitMinutes : poi.getSuggestDuration();
            visitSec[i] = visitMinutes * 60;

            OpenInfo openInfo = resolveOpenSegments(poi.getOpenHours(), dow);
            defaultOpen[i] = openInfo.unknown;
            if (openInfo.segments.isEmpty()) {
                closedPoiIds.add(poi.getId());
            }
            openSecSeconds[i] = toSeconds(openInfo.segments);
        }

        // 5. 精确枚举求解
        RouteEnumerator.SolveResult result = RouteEnumerator.solve(
                matrix, visitSec, openSecSeconds, startSec, lunchStartSec, lunchEndSec);
        if (result == null) {
            String detail = closedPoiIds.isEmpty()
                    ? "开放时间/午餐禁游约束下全部排列不可行"
                    : "以下景点当日闭馆: " + closedPoiIds;
            throw new BizException(ResultCode.PLAN_INFEASIBLE,
                    "该簇日内路径无可行解（" + input.getDate() + " " + dow + "，共 " + n + " 点）：" + detail);
        }

        // 6. 装配输出 + 人读日志
        RouteSolveOutput output = buildOutput(poiIds, visitSec, defaultDuration, defaultOpen,
                result, startSec);
        printSchedule(input, output);
        return output;
    }

    // ---------- 1. 参数校验 ----------

    private List<Long> validateAndDistinct(RouteSolveInput input) {
        if (input == null) {
            throw new BizException(ResultCode.PARAM_MISSING, "缺少必填参数: input");
        }
        if (input.getPoiIds() == null || input.getPoiIds().isEmpty()) {
            throw new BizException(ResultCode.PARAM_MISSING, "缺少必填参数: poiIds");
        }
        if (input.getPoiIds().stream().anyMatch(Objects::isNull)) {
            throw new BizException(ResultCode.PARAM_ILLEGAL, "poiIds 不允许包含 null");
        }
        if (input.getDate() == null) {
            throw new BizException(ResultCode.PARAM_MISSING, "缺少必填参数: date");
        }
        List<Long> distinct = input.getPoiIds().stream().distinct().toList();
        if (distinct.size() != input.getPoiIds().size()) {
            log.warn("输入 poiIds 含重复，已去重保序: {} -> {}", input.getPoiIds(), distinct);
        }
        return distinct;
    }

    private long[][] validateMatrix(long[][] matrix, int n) {
        if (matrix == null) {
            throw new BizException(ResultCode.PARAM_MISSING, "缺少必填参数: durationsSeconds");
        }
        if (matrix.length != n) {
            throw new BizException(ResultCode.PARAM_ILLEGAL,
                    "通勤矩阵行数须等于点数 " + n + "（实际 " + matrix.length + "）");
        }
        for (int i = 0; i < n; i++) {
            if (matrix[i] == null || matrix[i].length != n) {
                throw new BizException(ResultCode.PARAM_ILLEGAL, "通勤矩阵必须是 " + n + "×" + n + " 方阵");
            }
        }
        return matrix;
    }

    // ---------- 2. 查库 ----------

    private List<PoiEntity> loadPois(List<Long> poiIds) {
        Map<Long, PoiEntity> byId = new LinkedHashMap<>();
        for (PoiEntity entity : poiMapper.selectBatchIds(poiIds)) {
            byId.put(entity.getId(), entity);
        }
        List<Long> missing = poiIds.stream().filter(id -> !byId.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            throw new BizException(ResultCode.PARAM_MISSING, "POI 在库中不存在: " + missing);
        }
        return poiIds.stream().map(byId::get).toList();
    }

    // ---------- 3. open_hours 解析（三态语义，§4.6） ----------

    /**
     * 解析某 POI 在指定星期的开放时段（分钟）。
     *
     * @return unknown=true 表示该日开放时间未知（按 24h 处理）；
     *         unknown=false 且 segments 为空表示当日确定闭馆
     */
    private OpenInfo resolveOpenSegments(String openHours, DayOfWeek dow) {
        String key = dayKey(dow);
        if (openHours == null || openHours.isBlank()) {
            return OpenInfo.unknown(); // 整列空 = 未知 → 24h
        }
        final JsonNode root;
        try {
            root = MAPPER.readTree(openHours);
        } catch (Exception e) {
            log.warn("open_hours 非合法 JSON，按 24h 处理: {}", openHours, e);
            return OpenInfo.unknown();
        }
        if (!root.isObject()) {
            log.warn("open_hours 非对象，按 24h 处理");
            return OpenInfo.unknown();
        }
        JsonNode day = root.get(key);
        if (day == null || day.isNull()) {
            return OpenInfo.unknown(); // 该天未知 → 24h
        }
        if (!day.isArray()) {
            log.warn("open_hours.{} 非数组，按 24h 处理", key);
            return OpenInfo.unknown();
        }
        if (day.isEmpty()) {
            return OpenInfo.closed(); // 空数组 = 确定闭馆
        }
        List<int[]> segments = new ArrayList<>();
        for (JsonNode segment : day) {
            String open = segment.path("open").asText(null);
            String close = segment.path("close").asText(null);
            Integer openMin = parseMinute(open);
            Integer closeMin = parseMinute(close);
            if (openMin == null || closeMin == null || openMin >= closeMin) {
                log.warn("open_hours.{} 时段非法，整条按 24h 处理: {}-{}", key, open, close);
                return OpenInfo.unknown();
            }
            segments.add(new int[]{openMin, closeMin});
        }
        if (segments.isEmpty()) {
            return OpenInfo.closed();
        }
        // 防御：校验器已保证有序，这里再排序一次兜底（earliestStart 依赖升序）
        segments.sort(Comparator.comparingInt(a -> a[0]));
        return new OpenInfo(segments, false);
    }

    private String dayKey(DayOfWeek dow) {
        return switch (dow) {
            case MONDAY -> "mon";
            case TUESDAY -> "tue";
            case WEDNESDAY -> "wed";
            case THURSDAY -> "thu";
            case FRIDAY -> "fri";
            case SATURDAY -> "sat";
            case SUNDAY -> "sun";
        };
    }

    /** 解析 "HH:mm"；close 额外允许 "24:00"（= 1440）；非法返回 null */
    private Integer parseMinute(String text) {
        if (text == null) {
            return null;
        }
        if (END_OF_DAY.equals(text)) {
            return MINUTES_PER_DAY;
        }
        if (!HH_MM.matcher(text).matches()) {
            return null;
        }
        return Integer.parseInt(text.substring(0, 2)) * 60 + Integer.parseInt(text.substring(3, 5));
    }

    private int toSecondOfDay(LocalTime time) {
        return time.getHour() * 3600 + time.getMinute() * 60 + time.getSecond();
    }

    /** 分钟时段 → 秒时段（扁平日 [open0,close0,open1,close1,...]，新数组不改写共享常量） */
    private int[] toSeconds(List<int[]> minuteSegments) {
        int[] seconds = new int[minuteSegments.size() * 2];
        for (int i = 0; i < minuteSegments.size(); i++) {
            int[] seg = minuteSegments.get(i);
            seconds[i * 2] = seg[0] * 60;
            seconds[i * 2 + 1] = seg[1] * 60;
        }
        return seconds;
    }

    // ---------- 4. 输出装配 ----------

    private RouteSolveOutput buildOutput(List<Long> poiIds, int[] visitSec,
                                         boolean[] defaultDuration, boolean[] defaultOpen,
                                         RouteEnumerator.SolveResult result, int startSec) {
        int n = result.order.length;
        List<Long> orderedIds = new ArrayList<>(n);
        List<RouteStop> stops = new ArrayList<>(n);
        for (int pos = 0; pos < n; pos++) {
            int node = result.order[pos];
            orderedIds.add(poiIds.get(node));
            stops.add(RouteStop.builder()
                    .poiId(poiIds.get(node))
                    .arriveMinuteOfDay((int) (result.arriveSeconds[pos] / 60))
                    .departMinuteOfDay((int) (result.departSeconds[pos] / 60))
                    .visitMinutes(visitSec[node] / 60)
                    .waitSeconds(result.waitSecondsPerNode[pos])
                    .commuteFromPrevSeconds(result.commuteSecondsPerNode[pos])
                    .defaultDurationApplied(defaultDuration[node])
                    .defaultOpenHoursApplied(defaultOpen[node])
                    .build());
        }
        int elapsedMinutes = (int) ((result.departSeconds[n - 1] - startSec) / 60);
        return RouteSolveOutput.builder()
                .orderedPoiIds(orderedIds)
                .totalCommuteSeconds(result.commuteSeconds)
                .totalWaitSeconds(result.waitSeconds)
                .totalElapsedMinutes(elapsedMinutes)
                .stops(stops)
                .build();
    }

    // ---------- 5. 人读日志（AGENTS.md：console 可直接人工 review） ----------

    private void printSchedule(RouteSolveInput input, RouteSolveOutput output) {
        StringBuilder sb = new StringBuilder();
        sb.append(System.lineSeparator())
                .append("===== 日内路径求解结果（").append(input.getDate()).append(" ").append(input.getDate().getDayOfWeek()).append("）=====").append(System.lineSeparator())
                .append(String.format("站点数=%d | 总通勤=%ds | 总等待=%ds | 当日总耗时=%dmin%n",
                        output.getOrderedPoiIds().size(), output.getTotalCommuteSeconds(),
                        output.getTotalWaitSeconds(), output.getTotalElapsedMinutes()))
                .append(String.format("%-4s %-10s %-9s %-9s %-8s %-9s %-10s%n",
                        "序", "POI ID", "到达", "离开", "游玩min", "等待s", "通勤s"));
        int seq = 1;
        for (RouteStop stop : output.getStops()) {
            sb.append(String.format("%-4d %-10d %-9s %-9s %-8d %-9d %-10d%s%s%n",
                    seq++, stop.getPoiId(),
                    hhmm(stop.getArriveMinuteOfDay()), hhmm(stop.getDepartMinuteOfDay()),
                    stop.getVisitMinutes(), stop.getWaitSeconds(), stop.getCommuteFromPrevSeconds(),
                    stop.isDefaultDurationApplied() ? " [默认时长]" : "",
                    stop.isDefaultOpenHoursApplied() ? " [24h假设]" : ""));
        }
        log.info("{}", sb);
    }

    private String hhmm(int minuteOfDay) {
        return String.format("%02d:%02d", minuteOfDay / 60, minuteOfDay % 60);
    }

    /** 开放时段解析结果 */
    private static final class OpenInfo {
        /** 当日开放时段（分钟，open 升序）；空 = 闭馆 */
        final List<int[]> segments;
        /** true = 未知（按 24h 处理） */
        final boolean unknown;

        OpenInfo(List<int[]> segments, boolean unknown) {
            this.segments = segments;
            this.unknown = unknown;
        }

        static OpenInfo unknown() {
            return new OpenInfo(List.of(new int[]{0, MINUTES_PER_DAY}), true);
        }

        static OpenInfo closed() {
            return new OpenInfo(List.of(), false);
        }
    }
}
