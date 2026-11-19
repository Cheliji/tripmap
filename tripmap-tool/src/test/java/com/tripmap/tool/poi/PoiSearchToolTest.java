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
 *
 * <p>按 AGENTS.md 定规：每个用例打印场景、输入与输出结果表，console 可直接人工 review。</p>
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

    // ---------- 人读日志辅助 ----------

    /** 打印检索输出：候选池概况 + 排序结果表（名次/ID/名称/分数/rating/必去） */
    private void printOutput(String scenario, PoiSearchInput input, PoiSearchOutput out) {
        System.out.println("""
                
                ========== 场景：%s ==========""".formatted(scenario));
        System.out.printf("  输入: city=%s, 预算=%s, 时长上限=%s, 标签权重=%s, 分类权重=%s, 必去=%s, limit=%d%n",
                input.getCity(), input.getTicketPriceCap(), input.getSuggestDurationCapMinutes(),
                input.getTagWeights(), input.getCategoryWeights(),
                input.getMustVisitIds(), input.getLimit());
        System.out.printf("  池大小=%d, 池耗尽=%s, 必去冲突=%s%n",
                out.getPoolSize(), out.isPoolExhausted(), out.getMustVisitConflictIds());
        System.out.println("  ---- 召回结果（按输出顺序）----");
        System.out.printf("  %-4s %-6s %-16s %-8s %-8s %s%n", "名次", "ID", "名称", "总分", "rating", "必去");
        List<PoiCandidate> candidates = out.getCandidates();
        for (int i = 0; i < candidates.size(); i++) {
            PoiCandidate c = candidates.get(i);
            System.out.printf("  %-4d %-6d %-16s %+7.2f %-8s %s%n",
                    i + 1, c.getId(), c.getName(), c.getScore(),
                    c.getRating() == null ? "未知" : c.getRating().toPlainString(),
                    c.isMustVisit() ? "★" : "");
        }
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

    // ---------- 用例 ----------

    @Test
    @DisplayName("参数校验：city 为空抛 BizException（PARAM_MISSING）")
    void blankCityThrowsBizException() {
        System.out.println("\n========== 场景：city 为空 → 预期抛 BizException(100002) ==========");
        BizException e = assertThrows(BizException.class,
                () -> tool.execute(PoiSearchInput.builder().city(" ").build()));
        System.out.printf("  实际抛出: code=%d, message=%s%n", e.getCode(), e.getMessage());
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
        System.out.println("\n========== 场景：硬过滤 SQL（预算 200 元、时长上限 360 分钟） ==========");
        System.out.printf("  %s%n", sql);
        System.out.println("  预期含: city/status 等值 + (ticket_price IS NULL OR <= 200) + (suggest_duration IS NULL OR <= 360)");
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
        System.out.println("\n========== 场景：硬过滤 SQL（无限约束，仅 city+status） ==========");
        System.out.printf("  %s%n", sql);
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
        boolean pass = PoiSearchTool.passesHardFilter(unknown, input);
        System.out.println("""
                
                ========== 场景：NULL 三态（门票/时长未知 → 放行，不受约束影响） ==========""");
        System.out.printf("  POI: 冷门景点（门票=null, 时长=null）, 约束: 预算 100 / 时长 240 → 放行=%s（预期 true）%n", pass);
        assertTrue(pass);
    }

    @Test
    @DisplayName("边界：门票 0=免费放行；超预算/闭园/异城拦截")
    void hardFilterBoundaries() {
        PoiSearchInput input = PoiSearchInput.builder()
                .city("杭州")
                .ticketPriceCap(new BigDecimal("100"))
                .build();
        System.out.println("""
                
                ========== 场景：硬过滤边界（预算 100 元） ==========""");
        System.out.printf("  门票 0 元(免费) : 放行=%s（预期 true）%n",
                PoiSearchTool.passesHardFilter(poi(1L, "免费景点", null, null, "0", null), input));
        System.out.printf("  门票 200 元     : 放行=%s（预期 false 超预算）%n",
                PoiSearchTool.passesHardFilter(poi(2L, "贵景点", null, null, "200", null), input));
        PoiEntity closed = poi(3L, "闭园景点", null, null, "0", null);
        closed.setStatus(2);
        System.out.printf("  status=2(闭园)  : 放行=%s（预期 false）%n",
                PoiSearchTool.passesHardFilter(closed, input));
        PoiEntity otherCity = poi(4L, "异城景点", null, null, "0", null);
        otherCity.setCity("上海");
        System.out.printf("  city=上海(异城) : 放行=%s（预期 false）%n",
                PoiSearchTool.passesHardFilter(otherCity, input));
        assertTrue(PoiSearchTool.passesHardFilter(
                poi(1L, "免费景点", null, null, "0", null), input), "门票 0=免费应放行");
        assertFalse(PoiSearchTool.passesHardFilter(
                poi(2L, "贵景点", null, null, "200", null), input), "超预算应拦截");
        assertFalse(PoiSearchTool.passesHardFilter(closed, input), "闭园应拦截");
        assertFalse(PoiSearchTool.passesHardFilter(otherCity, input), "异城应拦截");
    }

    @Test
    @DisplayName("召回排序：打分降序；空偏好时 rating 降序兜底")
    void recallOrdersByScoreThenRating() {
        PoiEntity highRating = poi(1L, "高分景点", null, "4.9", null, null);
        PoiEntity tagged = poi(2L, "亲子乐园", "[\"亲子\"]", "3.0", null, null);
        when(poiMapper.selectList(any())).thenReturn(List.of(highRating, tagged));
        when(poiMapper.selectBatchIds(anyCollection())).thenReturn(List.of());

        // 空偏好：无标签/category 权重 → 两者打分仅 rating 之差，高分在前
        PoiSearchOutput out = tool.execute(baseInput());
        printOutput("空偏好兜底（预期：按 rating 降序 → 高分景点第一）", baseInput(), out);
        assertEquals(List.of(1L, 2L),
                out.getCandidates().stream().map(PoiCandidate::getId).toList());

        // 给"亲子"高权重：标签命中反超
        PoiSearchInput weighted = PoiSearchInput.builder()
                .city("杭州")
                .tagWeights(Map.of(PoiTagVocab.FAMILY, 5.0))
                .build();
        PoiSearchOutput out2 = tool.execute(weighted);
        printOutput("偏好加权（亲子 +5，预期：标签命中反超 rating 兜底）", weighted, out2);
        assertEquals(List.of(2L, 1L),
                out2.getCandidates().stream().map(PoiCandidate::getId).toList());
    }

    @Test
    @DisplayName("平分裁决：score 相同比 rating，rating 相同比 id 升序（确定性）")
    void tieBreakDeterministic() {
        PoiEntity a = poi(2L, "景点B", null, "4.5", null, null);
        PoiEntity b = poi(1L, "景点A", null, "4.5", null, null);
        PoiEntity c = poi(3L, "景点C", null, "4.8", null, null);
        when(poiMapper.selectList(any())).thenReturn(List.of(a, b, c));
        when(poiMapper.selectBatchIds(anyCollection())).thenReturn(List.of());

        // 三者打分都等于 rating 映射分：C(4.8) 前；A/B 平 rating → id 升序 A(1) 在 B(2) 前
        PoiSearchOutput out = tool.execute(baseInput());
        printOutput("平分裁决（预期顺序：C 4.8 → A id=1 → B id=2）", baseInput(), out);
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
        printOutput("必去钉住（必去冷门 rating 2.0，预期：★ 恒在第一名，不受截断影响）", input, out);
        assertEquals(99L, out.getCandidates().get(0).getId(), "必去应钉在头部");
        assertTrue(out.getCandidates().get(0).isMustVisit());
        assertTrue(out.getMustVisitConflictIds().isEmpty());
        assertEquals(31, out.getPoolSize());
    }

    @Test
    @DisplayName("必去冲突：闭园/库中不存在的 ID 进 mustVisitConflictIds，不进候选")
    void mustVisitConflictDetected() {
        PoiEntity inPool = poi(1L, "普通景点", null, "4.5", null, null);
        PoiEntity closed = poi(2L, "闭园必去", null, "4.5", null, null);
        closed.setStatus(2);
        when(poiMapper.selectList(any())).thenReturn(List.of(inPool));
        when(poiMapper.selectBatchIds(anyCollection())).thenReturn(List.of(closed));

        PoiSearchInput input = PoiSearchInput.builder()
                .city("杭州")
                .mustVisitIds(List.of(2L, 3L)) // 2L 闭园、3L 库中不存在
                .build();
        PoiSearchOutput out = tool.execute(input);
        printOutput("必去冲突（2L 闭园、3L 不存在，预期：冲突列表 [2, 3]，候选只剩普通景点）", input, out);
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
        printOutput("大池截断（池 45 > 上限 40，预期：取前 40，poolExhausted=false）", baseInput(), big);
        assertEquals(40, big.getCandidates().size(), "默认 limit=40 应截断");
        assertFalse(big.isPoolExhausted());

        List<PoiEntity> smallPool = bigPool.subList(0, 10);
        when(poiMapper.selectList(any())).thenReturn(smallPool);
        PoiSearchOutput small = tool.execute(baseInput());
        printOutput("小池耗尽（池 10 < 下限 20，预期：全量返回 10 条，poolExhausted=true）", baseInput(), small);
        assertEquals(10, small.getCandidates().size(), "池不足下限应全量返回");
        assertTrue(small.isPoolExhausted());
        assertEquals(10, small.getPoolSize());
    }

    @Test
    @DisplayName("tags JSON 解析失败按无标签处理，不中断召回")
    void malformedTagsTolerated() {
        PoiEntity broken = poi(1L, "坏标签景点", "not-json", "4.5", null, null);
        when(poiMapper.selectList(any())).thenReturn(List.of(broken));
        when(poiMapper.selectBatchIds(anyCollection())).thenReturn(List.of());

        PoiSearchOutput out = tool.execute(baseInput());
        System.out.println("""
                
                ========== 场景：tags JSON 损坏（预期：按无标签处理，不中断召回） ==========""");
        System.out.printf("  召回 %d 条，tags 解析为: %s%n",
                out.getCandidates().size(), out.getCandidates().get(0).getTags());
        assertEquals(1, out.getCandidates().size());
        assertTrue(out.getCandidates().get(0).getTags().isEmpty());
    }

    @Test
    @DisplayName("工具名取自 ToolNames 常量")
    void toolNameFromConstants() {
        System.out.println("\n========== 场景：工具名注册 ==========");
        System.out.printf("  name() = %s（预期 poi_search）%n", tool.name());
        assertEquals(com.tripmap.tool.spi.ToolNames.POI_SEARCH, tool.name());
    }
}
