package com.tripmap.tool.tsp;

import com.tripmap.common.exception.BizException;
import com.tripmap.common.result.ResultCode;
import com.tripmap.dal.entity.PoiEntity;
import com.tripmap.dal.mapper.PoiMapper;
import com.tripmap.tool.tsp.config.RouteSolveProperties;
import com.tripmap.tool.tsp.dto.RouteSolveInput;
import com.tripmap.tool.tsp.dto.RouteSolveOutput;
import com.tripmap.tool.tsp.dto.RouteStop;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 日内路径求解工具单测（任务 2.6，PoiMapper 走 Mockito 桩，无真实 DB）。
 *
 * <p>覆盖：open_hours 三态解析（null→24h / 24:00 / 正常时段）、suggest_duration
 * 缺省回退默认时长、闭馆日无解（300002）、POI 不存在、矩阵维度非法、
 * input.defaultVisitMinutes 覆盖配置默认值。</p>
 *
 * <p>按 AGENTS.md 定规：每个用例打印场景、输入与输出，console 可直接人工 review。</p>
 */
class RouteSolveToolTest {

    private final PoiMapper poiMapper = mock(PoiMapper.class);
    private final RouteSolveProperties properties = new RouteSolveProperties();
    private final RouteSolveTool tool = new RouteSolveTool(poiMapper, properties);

    // ---------- 主路径 ----------

    @Test
    @DisplayName("主路径：三态 open_hours + 时长缺省回退，输出逐站明细与标记")
    void happyPathWithMixedOpenHoursAndDefaults() {
        System.out.println("【场景】3 点：A=null open_hours(24h假设)+null 时长(默认90min)；"
                + "B=09:00-17:00+120min；C=00:00-24:00+60min；通勤 d(AB)=600 d(BC)=900 d(AC)=1500s；"
                + "09:00 出发、午餐禁用");
        PoiEntity poiA = poi(101L, "西湖", null, null);
        PoiEntity poiB = poi(102L, "灵隐寺", allDays("09:00", "17:00"), 120);
        PoiEntity poiC = poi(103L, "河坊街", allDays("00:00", "24:00"), 60);
        when(poiMapper.selectBatchIds(anyList())).thenReturn(List.of(poiA, poiB, poiC));

        long[][] matrix = {
                {0, 600, 1500},
                {600, 0, 900},
                {1500, 900, 0}
        };
        RouteSolveInput input = RouteSolveInput.builder()
                .poiIds(List.of(101L, 102L, 103L))
                .durationsSeconds(matrix)
                .date(LocalDate.of(2026, 11, 23))
                .startTime(LocalTime.of(9, 0))
                .lunchStart(LocalTime.of(12, 0))
                .lunchEnd(LocalTime.of(12, 0)) // 禁用午餐
                .build();

        RouteSolveOutput out = tool.execute(input);

        // 最优开放路径 A-B-C（通勤 600+900=1500s）
        assertEquals(List.of(101L, 102L, 103L), out.getOrderedPoiIds());
        assertEquals(1500L, out.getTotalCommuteSeconds());
        assertEquals(0L, out.getTotalWaitSeconds());
        assertEquals(295, out.getTotalElapsedMinutes());

        List<RouteStop> stops = out.getStops();
        assertEquals(3, stops.size());

        RouteStop a = stops.get(0);
        assertEquals(101L, a.getPoiId());
        assertEquals(540, a.getArriveMinuteOfDay());   // 09:00
        assertEquals(630, a.getDepartMinuteOfDay());   // 10:30
        assertEquals(90, a.getVisitMinutes());
        assertEquals(0L, a.getCommuteFromPrevSeconds());
        assertTrue(a.isDefaultDurationApplied());
        assertTrue(a.isDefaultOpenHoursApplied());

        RouteStop b = stops.get(1);
        assertEquals(102L, b.getPoiId());
        assertEquals(640, b.getArriveMinuteOfDay());   // 10:40
        assertEquals(760, b.getDepartMinuteOfDay());   // 12:40
        assertEquals(120, b.getVisitMinutes());
        assertEquals(600L, b.getCommuteFromPrevSeconds());
        assertFalse(b.isDefaultDurationApplied());
        assertFalse(b.isDefaultOpenHoursApplied());

        RouteStop c = stops.get(2);
        assertEquals(103L, c.getPoiId());
        assertEquals(775, c.getArriveMinuteOfDay());   // 12:55
        assertEquals(835, c.getDepartMinuteOfDay());   // 13:55
        assertEquals(60, c.getVisitMinutes());
        assertEquals(900L, c.getCommuteFromPrevSeconds());
        assertFalse(c.isDefaultDurationApplied());
        assertFalse(c.isDefaultOpenHoursApplied());
    }

    @Test
    @DisplayName("闭馆日：当日 [] 的景点导致全部排列不可行 → 300002")
    void closedDayThrowsPlanInfeasible() {
        LocalDate date = LocalDate.of(2026, 11, 23);
        System.out.println("【场景】" + date + "（" + date.getDayOfWeek() + "）B 当日闭馆，A 24h → 无解");
        PoiEntity poiA = poi(101L, "西湖", null, 60);
        PoiEntity poiB = poi(102L, "某闭馆点", closedOn(date.getDayOfWeek()), 60);
        when(poiMapper.selectBatchIds(anyList())).thenReturn(List.of(poiA, poiB));

        RouteSolveInput input = RouteSolveInput.builder()
                .poiIds(List.of(101L, 102L))
                .durationsSeconds(new long[][]{{0, 600}, {600, 0}})
                .date(date)
                .startTime(LocalTime.of(9, 0))
                .lunchStart(LocalTime.of(12, 0))
                .lunchEnd(LocalTime.of(12, 0))
                .build();

        BizException ex = assertThrows(BizException.class, () -> tool.execute(input));
        assertEquals(ResultCode.PLAN_INFEASIBLE.getCode(), ex.getCode());
        System.out.println("抛出 BizException code=" + ex.getCode() + " message=" + ex.getMessage());
    }

    @Test
    @DisplayName("POI 在库中不存在 → 100002")
    void missingPoiThrowsParamMissing() {
        System.out.println("【场景】输入 2 个 POI，库中仅 1 个 → 参数缺失");
        when(poiMapper.selectBatchIds(anyList())).thenReturn(List.of(poi(101L, "西湖", null, 60)));

        RouteSolveInput input = RouteSolveInput.builder()
                .poiIds(List.of(101L, 102L))
                .durationsSeconds(new long[][]{{0, 600}, {600, 0}})
                .date(LocalDate.of(2026, 11, 23))
                .build();

        BizException ex = assertThrows(BizException.class, () -> tool.execute(input));
        assertEquals(ResultCode.PARAM_MISSING.getCode(), ex.getCode());
        System.out.println("抛出 BizException code=" + ex.getCode() + " message=" + ex.getMessage());
    }

    @Test
    @DisplayName("矩阵维度非法 → 100003")
    void matrixDimensionMismatchThrowsParamIllegal() {
        System.out.println("【场景】2 个 POI 但矩阵是 1×1 → 参数格式非法");
        when(poiMapper.selectBatchIds(anyList())).thenReturn(List.of(
                poi(101L, "西湖", null, 60), poi(102L, "灵隐寺", null, 60)));

        RouteSolveInput input = RouteSolveInput.builder()
                .poiIds(List.of(101L, 102L))
                .durationsSeconds(new long[][]{{0}})
                .date(LocalDate.of(2026, 11, 23))
                .build();

        BizException ex = assertThrows(BizException.class, () -> tool.execute(input));
        assertEquals(ResultCode.PARAM_ILLEGAL.getCode(), ex.getCode());
        System.out.println("抛出 BizException code=" + ex.getCode() + " message=" + ex.getMessage());
    }

    @Test
    @DisplayName("input.defaultVisitMinutes 覆盖配置默认值（LLM 传入）")
    void inputDefaultVisitMinutesOverridesConfig() {
        System.out.println("【场景】单点 null 时长，input 传 60min（配置默认 90min）→ 用 60min");
        when(poiMapper.selectBatchIds(anyList())).thenReturn(List.of(poi(101L, "西湖", null, null)));

        RouteSolveInput input = RouteSolveInput.builder()
                .poiIds(List.of(101L))
                .durationsSeconds(new long[][]{{0}})
                .date(LocalDate.of(2026, 11, 23))
                .startTime(LocalTime.of(9, 0))
                .lunchStart(LocalTime.of(12, 0))
                .lunchEnd(LocalTime.of(12, 0))
                .defaultVisitMinutes(60)
                .build();

        RouteSolveOutput out = tool.execute(input);
        assertEquals(60, out.getStops().get(0).getVisitMinutes());
        assertTrue(out.getStops().get(0).isDefaultDurationApplied());
        System.out.printf("visitMinutes=%d defaultDurationApplied=%b%n",
                out.getStops().get(0).getVisitMinutes(), out.getStops().get(0).isDefaultDurationApplied());
    }

    // ---------- 辅助 ----------

    private static PoiEntity poi(long id, String name, String openHours, Integer suggestDuration) {
        return PoiEntity.builder()
                .id(id)
                .name(name)
                .openHours(openHours)
                .suggestDuration(suggestDuration)
                .build();
    }

    /** 七天同一时段的 open_hours JSON */
    private static String allDays(String open, String close) {
        String seg = "[{\"open\":\"" + open + "\",\"close\":\"" + close + "\"}]";
        return seven(seg, seg, seg, seg, seg, seg, seg);
    }

    private static String seven(String mon, String tue, String wed, String thu, String fri, String sat, String sun) {
        return "{\"mon\":" + mon + ",\"tue\":" + tue + ",\"wed\":" + wed + ",\"thu\":" + thu
                + ",\"fri\":" + fri + ",\"sat\":" + sat + ",\"sun\":" + sun + "}";
    }

    /** 目标星期闭馆（[]），其余 6 天 24h */
    private static String closedOn(DayOfWeek dow) {
        String open24 = "[{\"open\":\"00:00\",\"close\":\"24:00\"}]";
        String target = dayKey(dow);
        String[] keys = {"mon", "tue", "wed", "thu", "fri", "sat", "sun"};
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < keys.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("\"").append(keys[i]).append("\":");
            sb.append(keys[i].equals(target) ? "[]" : open24);
        }
        return sb.append("}").toString();
    }

    private static String dayKey(DayOfWeek dow) {
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
}
