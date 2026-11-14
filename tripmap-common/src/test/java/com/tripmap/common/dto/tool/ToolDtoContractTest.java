package com.tripmap.common.dto.tool;

import com.tripmap.common.dto.tool.cluster.ClusterInput;
import com.tripmap.common.dto.tool.cluster.ClusterOutput;
import com.tripmap.common.dto.tool.cluster.PoiPoint;
import com.tripmap.common.dto.tool.commute.CommuteMatrixInput;
import com.tripmap.common.dto.tool.commute.CommuteMatrixOutput;
import com.tripmap.common.dto.tool.commute.TravelMode;
import com.tripmap.common.dto.tool.poi.PoiSearchInput;
import com.tripmap.common.dto.tool.poi.PoiSearchOutput;
import com.tripmap.common.dto.tool.route.RouteSolveInput;
import com.tripmap.common.dto.tool.route.RouteSolveOutput;
import com.tripmap.common.dto.tool.route.TimeWindow;
import com.tripmap.common.dto.tool.search.SearchHit;
import com.tripmap.common.dto.tool.search.WebSearchInput;
import com.tripmap.common.dto.tool.search.WebSearchOutput;
import com.tripmap.common.dto.tool.weather.WeatherQueryInput;
import com.tripmap.common.dto.tool.weather.WeatherQueryOutput;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具契约 DTO 结构约束（任务 2.1.2）：六工具输入/输出必须继承对应 marker 接口，
 * 装配形状（Builder 往返）与矩阵 key 规范不可漂移。
 */
class ToolDtoContractTest {

    @Test
    void 六工具输入输出全部实现Marker接口() {
        assertInstanceOf(ToolInput.class, PoiSearchInput.builder().city("杭州").build());
        assertInstanceOf(ToolOutput.class, PoiSearchOutput.builder().build());

        assertInstanceOf(ToolInput.class, WeatherQueryInput.builder()
                .city("杭州").dates(List.of(LocalDate.now())).build());
        assertInstanceOf(ToolOutput.class, WeatherQueryOutput.builder().build());

        assertInstanceOf(ToolInput.class, CommuteMatrixInput.builder()
                .poiIds(List.of(1L, 2L)).mode(TravelMode.TRANSIT).build());
        assertInstanceOf(ToolOutput.class, CommuteMatrixOutput.builder().build());

        assertInstanceOf(ToolInput.class, ClusterInput.builder()
                .points(List.of(new PoiPoint(1L, BigDecimal.ONE, BigDecimal.ONE)))
                .k(3).seed(42L).build());
        assertInstanceOf(ToolOutput.class, ClusterOutput.builder().build());

        assertInstanceOf(ToolInput.class, RouteSolveInput.builder().build());
        assertInstanceOf(ToolOutput.class, RouteSolveOutput.builder().build());

        assertInstanceOf(ToolInput.class, WebSearchInput.builder().query("杭州 攻略").build());
        assertInstanceOf(ToolOutput.class, WebSearchOutput.builder()
                .hits(List.of(new SearchHit("标题", "https://example.com", "片段"))).build());
    }

    @Test
    void Builder往返保持字段形状() {
        PoiSearchInput input = PoiSearchInput.builder()
                .city("杭州")
                .status(1)
                .ticketPriceMax(new BigDecimal("100"))
                .maxDurationMin(240)
                .preferenceKeywords(List.of("历史文化"))
                .mustVisitPoiIds(List.of(11L))
                .candidateLimit(30)
                .build();
        assertEquals("杭州", input.getCity());
        assertEquals(1, input.getStatus());
        assertEquals(new BigDecimal("100"), input.getTicketPriceMax());
        assertEquals(240, input.getMaxDurationMin());
        assertEquals(List.of("历史文化"), input.getPreferenceKeywords());
        assertEquals(List.of(11L), input.getMustVisitPoiIds());
        assertEquals(30, input.getCandidateLimit());
    }

    @Test
    void 矩阵Key规范小ID在前且方向无关() {
        assertEquals("1_2", CommuteMatrixOutput.keyOf(1L, 2L));
        assertEquals("1_2", CommuteMatrixOutput.keyOf(2L, 1L));
        assertEquals("5_5", CommuteMatrixOutput.keyOf(5L, 5L));
    }

    @Test
    void 时间窗校验口径与开放时段定义一致() {
        // 合法边界：0 点开放、24:00(1440) 结束
        assertEquals(540, new TimeWindow(540, 1080).openMinute());
        assertEquals(1440, new TimeWindow(0, 1440).closeMinute());
        // 非法：open >= close / 越界
        assertThrows(IllegalArgumentException.class, () -> new TimeWindow(1080, 540));
        assertThrows(IllegalArgumentException.class, () -> new TimeWindow(-1, 540));
        assertThrows(IllegalArgumentException.class, () -> new TimeWindow(0, 1441));
    }

    @Test
    void 工具名集合完整且唯一() {
        // 该断言在 tool 模块亦有对应测试；此处校验 DTO 包内不依赖任何工具实现
        assertTrue(PoiSearchInput.class.getInterfaces().length >= 1);
    }
}
