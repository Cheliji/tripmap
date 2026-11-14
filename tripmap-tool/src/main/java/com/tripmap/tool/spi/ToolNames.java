package com.tripmap.tool.spi;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工具名常量（任务 2.1.1）：节点日志埋点、SSE 进度（node_progress）与
 * 评测回退路由（设计说明书 §7.3）的统一标识。
 *
 * <p>注册方式：各工具实现 {@link TripTool} 并以 {@code @Component} 注册为
 * Spring Bean，M3 的 StateGraph 节点按接口构造器注入；name() 返回值必须
 * 取自本常量类，防止散落字符串漂移。</p>
 */
public final class ToolNames {

    /** POI 检索工具（任务 2.2） */
    public static final String POI_SEARCH = "poi_search";
    /** 天气查询工具（任务 2.3） */
    public static final String WEATHER_QUERY = "weather_query";
    /** 通勤矩阵工具（任务 2.4） */
    public static final String COMMUTE_MATRIX = "commute_matrix";
    /** 空间聚类工具（任务 2.5） */
    public static final String SPATIAL_CLUSTER = "spatial_cluster";
    /** 日内路径求解工具（任务 2.6，双开放最短路径精确枚举） */
    public static final String ROUTE_SOLVE = "route_solve";
    /** 联网搜索工具（任务 2.7） */
    public static final String WEB_SEARCH = "web_search";

    private ToolNames() {
    }

    /** 全部工具名（埋点与巡检用） */
    public static Set<String> all() {
        return Set.copyOf(Arrays.asList(POI_SEARCH, WEATHER_QUERY, COMMUTE_MATRIX,
                SPATIAL_CLUSTER, ROUTE_SOLVE, WEB_SEARCH));
    }
}
