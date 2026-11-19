package com.tripmap.tool.poi;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.tripmap.common.constant.PoiTagVocab;
import com.tripmap.common.exception.BizException;
import com.tripmap.dal.entity.PoiEntity;
import com.tripmap.dal.mapper.PoiMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * POI 检索工具单测（任务 2.2.3）。
 *
 * <p>Mapper 走 Mockito 桩（无真实 DB）；SQL 语义通过
 * {@code getCustomSqlSegment()} 断言（需先初始化 MyBatis-Plus TableInfo 缓存）。</p>
 */
class PoiSearchToolTest {

    private final PoiMapper poiMapper = mock(PoiMapper.class);
    private final PoiSearchProperties properties = new PoiSearchProperties();
    private final PoiSearchTool tool =
            new PoiSearchTool(poiMapper, new PoiRecallScorer(properties), properties);

    @BeforeAll
    static void initTableInfo() {
        // LambdaQueryWrapper 列名解析依赖 TableInfo 缓存，纯单测环境手动初始化
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), PoiEntity.class);
    }

    /** 造一个杭州/正常的 POI 实体 */
    private PoiEntity poi(long id, String name, String tagsJson, String rating,
                          String ticket, Integer duration) {
        return PoiEntity.builder()
                .id(id)
                .name(name)
                .city("杭州")
                .status(1)
                .tags(tagsJson)
                .rating(rating == null ? null : new BigDecimal(rating))
                .ticketPrice(ticket == null ? null : new BigDecimal(ticket))
                .suggestDuration(duration)
                .build();
    }

    private PoiSearchInput baseInput() {
        return PoiSearchInput.builder().city("杭州").build();
    }

    @Test
    @DisplayName("参数校验：city 为空抛 BizException（PARAM_MISSING）")
    void blankCityThrowsBizException() {
        BizException e = assertThrows(BizException.class,
                () -> tool.execute(PoiSearchInput.builder().city(" ").build()));
        assertEquals(100002, e.getCode());
    }

    @Test
    @DisplayName("SQL 过滤：含 city/status 与 NULL 放行的门票/时长条件")
    void filterSqlContainsNullOrConditions() {
        PoiSearchInput input = PoiSearchInput.builder()
                .city("杭州")
                .ticketPriceCap(new BigDecimal("200"))
                .suggestDurationCapMinutes(360)
                .build();
        String sql = PoiSearchTool.buildFilterWrapper(input).getCustomSqlSegment();
        assertTrue(sql.contains("city"), sql);
        assertTrue(sql.contains("status"), sql);
        assertTrue(sql.contains("ticket_price IS NULL"), sql);
        assertTrue(sql.contains("ticket_price <="), sql);
        assertTrue(sql.contains("suggest_duration IS NULL"), sql);
        assertTrue(sql.contains("suggest_duration <="), sql);
    }

    @Test
    @DisplayName("SQL 过滤：未设上限时下推条件不含对应字段")
    void filterSqlOmitsAbsentCaps() {
        String sql = PoiSearchTool.buildFilterWrapper(baseInput()).getCustomSqlSegment();
        assertFalse(sql.contains("ticket_price"), sql);
        assertFalse(sql.contains("suggest_duration"), sql);
    }

    @Test
    @DisplayName("NULL 三态：门票/时长为 null 的 POI 放行（passesHardFilter）")
    void nullFieldsPassHardFilter() {
        PoiEntity unknown = poi(1L, "冷门景点", null, null, null, null);
        PoiSearchInput input = PoiSearchInput.builder()
                .city("杭州")
                .ticketPriceCap(new BigDecimal("100"))
                .suggestDurationCapMinutes(240)
                .build();
        assertTrue(PoiSearchTool.passesHardFilter(unknown, input));
    }

    @Test
    @DisplayName("边界：门票 0=免费放行；超预算/闭园/异城拦截")
    void hardFilterBoundaries() {
        PoiSearchInput input = PoiSearchInput.builder()
                .city("杭州")
                .ticketPriceCap(new BigDecimal("100"))
                .build();
        assertTrue(PoiSearchTool.passesHardFilter(
                poi(1L, "免费景点", null, null, "0", null), input), "门票 0=免费应放行");
        assertFalse(PoiSearchTool.passesHardFilter(
                poi(2L, "贵景点", null, null, "200", null), input), "超预算应拦截");
        PoiEntity closed = poi(3L, "闭园景点", null, null, "0", null);
        closed.setStatus(2);
        assertFalse(PoiSearchTool.passesHardFilter(closed, input), "闭园应拦截");
        PoiEntity otherCity = poi(4L, "异城景点", null, null, "0", null);
        otherCity.setCity("上海");
        assertFalse(PoiSearchTool.passesHardFilter(otherCity, input), "异城应拦截");
    }

    @Test
    @DisplayName("召回排序：打分降序；空偏好时 rating 降序兜底")
    void recallOrdersByScoreThenRating() {
        PoiEntity highRating = poi(1L, "高分", null, "4.9", null, null);
        PoiEntity tagged = poi(2L, "亲子", "[\"亲子\"]", "3.0", null, null);
        when(poiMapper.selectList(any())).thenReturn(List.of(highRating, tagged));
        when(poiMapper.selectBatchIds(anyCollection())).thenReturn(List.of());

        // 空偏好：无标签/category 权重 → 两者打分仅 rating 之差，高分在前
        PoiSearchOutput out = tool.execute(baseInput());
        assertEquals(List.of(1L, 2L),
                out.getCandidates().stream().map(PoiCandidate::getId).toList());

        // 给"亲子"高权重：标签命中反超
        PoiSearchInput weighted = PoiSearchInput.builder()
                .city("杭州")
                .tagWeights(Map.of(PoiTagVocab.FAMILY, 5.0))
                .build();
        PoiSearchOutput out2 = tool.execute(weighted);
        assertEquals(List.of(2L, 1L),
                out2.getCandidates().stream().map(PoiCandidate::getId).toList());
    }

    @Test
    @DisplayName("平分裁决：score 相同比 rating，rating 相同比 id 升序（确定性）")
    void tieBreakDeterministic() {
        PoiEntity a = poi(2L, "B", null, "4.5", null, null);
        PoiEntity b = poi(1L, "A", null, "4.5", null, null);
        PoiEntity c = poi(3L, "C", null, "4.8", null, null);
        when(poiMapper.selectList(any())).thenReturn(List.of(a, b, c));
        when(poiMapper.selectBatchIds(anyCollection())).thenReturn(List.of());

        // 三者打分都等于 rating 映射分：C(4.8) 前；A/B 平 rating → id 升序 A(1) 在 B(2) 前
        PoiSearchOutput out = tool.execute(baseInput());
        assertEquals(List.of(3L, 1L, 2L),
                out.getCandidates().stream().map(PoiCandidate::getId).toList());
    }

    @Test
    @DisplayName("必去钉住：低分必去项恒在头部且不受截断影响")
    void mustVisitPinned() {
        List<PoiEntity> pool = new java.util.ArrayList<>();
        for (long i = 1; i <= 30; i++) {
            pool.add(poi(i, "景点" + i, null, "4.7", null, null));
        }
        PoiEntity must = poi(99L, "必去冷门", null, "2.0", null, null);
        pool.add(must);
        when(poiMapper.selectList(any())).thenReturn(pool);
        when(poiMapper.selectBatchIds(anyCollection())).thenReturn(List.of(must));

        PoiSearchInput input = PoiSearchInput.builder()
                .city("杭州")
                .mustVisitIds(List.of(99L))
                .build();
        PoiSearchOutput out = tool.execute(input);
        assertEquals(99L, out.getCandidates().get(0).getId(), "必去应钉在头部");
        assertTrue(out.getCandidates().get(0).isMustVisit());
        assertTrue(out.getMustVisitConflictIds().isEmpty());
        assertEquals(31, out.getPoolSize());
    }

    @Test
    @DisplayName("必去冲突：闭园/库中不存在的 ID 进 mustVisitConflictIds，不进候选")
    void mustVisitConflictDetected() {
        PoiEntity inPool = poi(1L, "普通", null, "4.5", null, null);
        PoiEntity closed = poi(2L, "闭园必去", null, "4.5", null, null);
        closed.setStatus(2);
        when(poiMapper.selectList(any())).thenReturn(List.of(inPool));
        when(poiMapper.selectBatchIds(anyCollection())).thenReturn(List.of(closed));

        PoiSearchInput input = PoiSearchInput.builder()
                .city("杭州")
                .mustVisitIds(List.of(2L, 3L)) // 3L 库中不存在
                .build();
        PoiSearchOutput out = tool.execute(input);
        assertEquals(List.of(2L, 3L), out.getMustVisitConflictIds());
        assertEquals(List.of(1L), out.getCandidates().stream().map(PoiCandidate::getId).toList());
    }

    @Test
    @DisplayName("截断与池耗尽：池超上限截到 40；池不足 20 全量返回并置 poolExhausted")
    void truncationAndPoolExhausted() {
        List<PoiEntity> bigPool = new java.util.ArrayList<>();
        for (long i = 1; i <= 45; i++) {
            bigPool.add(poi(i, "景点" + i, null, "4.5", null, null));
        }
        when(poiMapper.selectList(any())).thenReturn(bigPool);
        when(poiMapper.selectBatchIds(anyCollection())).thenReturn(List.of());

        PoiSearchOutput big = tool.execute(baseInput());
        assertEquals(40, big.getCandidates().size(), "默认 limit=40 应截断");
        assertFalse(big.isPoolExhausted());

        List<PoiEntity> smallPool = bigPool.subList(0, 10);
        when(poiMapper.selectList(any())).thenReturn(smallPool);
        PoiSearchOutput small = tool.execute(baseInput());
        assertEquals(10, small.getCandidates().size(), "池不足下限应全量返回");
        assertTrue(small.isPoolExhausted());
        assertEquals(10, small.getPoolSize());
    }

    @Test
    @DisplayName("tags JSON 解析失败按无标签处理，不中断召回")
    void malformedTagsTolerated() {
        PoiEntity broken = poi(1L, "坏标签", "not-json", "4.5", null, null);
        when(poiMapper.selectList(any())).thenReturn(List.of(broken));
        when(poiMapper.selectBatchIds(anyCollection())).thenReturn(List.of());

        PoiSearchOutput out = tool.execute(baseInput());
        assertEquals(1, out.getCandidates().size());
        assertTrue(out.getCandidates().get(0).getTags().isEmpty());
    }

    @Test
    @DisplayName("工具名取自 ToolNames 常量")
    void toolNameFromConstants() {
        assertEquals(com.tripmap.tool.spi.ToolNames.POI_SEARCH, tool.name());
    }
}
