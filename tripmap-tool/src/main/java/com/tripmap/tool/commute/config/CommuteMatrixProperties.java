package com.tripmap.tool.commute.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 通勤矩阵工具配置（任务 2.4）。
 *
 * <p>算路策略、缺失率阈值、并行度、TTL 全部配置化（调优只改 application.yml）；
 * 城市→高德 citycode 映射按 2026-11 思路与天气一致（MVP 只有杭州，M5 扩城市时
 * 只加配置条目，零代码改动）。</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "tripmap.commute")
public class CommuteMatrixProperties {

    /** Redis 缓存 TTL（天）。设计说明书 §8.3：通勤矩阵 7 天 */
    private int cacheTtlDays = 7;

    /** 驾车算路策略（amap-route-api.md §2.2：32 = 高德推荐） */
    private int drivingStrategy = 32;

    /** 公交换乘策略（amap-route-api.md §3.2：0 = 推荐模式） */
    private int transitStrategy = 0;

    /** 公交返回方案条数（矩阵只取最优一条，减少数据量） */
    private int alternativeRoute = 1;

    /** 缺失率阈值：缺失对占比超过此值判该簇规划不可行（300002，交回上游对话） */
    private double missingRatioThreshold = 0.2;

    /** 簇内两两查询并行度（总闸仍是高德 QPS 令牌桶，见 AmapClient） */
    private int maxConcurrency = 8;

    /** Redis 缓存键前缀（完整 key = prefix + mode + ":小id:大id"，id 排序规范化） */
    private String cacheKeyPrefix = "tripmap:commute:";

    /**
     * 城市 → 高德 citycode 映射（列表式）。
     *
     * <p>2026-11 由 {@code Map<String,String>} 改为 {@code List<CityCode>}：
     * Spring Boot 的 {@code ConfigurationPropertyName} 无法绑定 YAML 中
     * 非 ASCII 的 Map 键（中文"杭州"键被当成标量，整张 Map 绑定失败、
     * ApplicationContext 起不来）；改列表后中文只出现在 value，绑定正常，
     * M5 扩城市仍零代码只加条目。</p>
     */
    private List<CityCode> cityCodes = defaultCityCodes();

    private static List<CityCode> defaultCityCodes() {
        return new ArrayList<>(List.of(CityCode.builder().city("杭州").code("0571").build()));
    }

    /**
     * 按 POI 的城市名解析高德 citycode：兼容库中"杭州市"与输入"杭州"两种口径。
     *
     * @return citycode；未配置的城市返回 null（调用方按不支持处理）
     */
    public String resolveCityCode(String city) {
        if (city == null) {
            return null;
        }
        String normalized = city.endsWith("市") ? city.substring(0, city.length() - 1) : city;
        for (CityCode entry : cityCodes) {
            if (entry == null || entry.getCity() == null) {
                continue;
            }
            String key = entry.getCity().endsWith("市")
                    ? entry.getCity().substring(0, entry.getCity().length() - 1) : entry.getCity();
            if (normalized.equals(key)) {
                return entry.getCode();
            }
        }
        return null;
    }

    /** 城市→高德 citycode 配置条目 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CityCode {
        /** 城市名（如"杭州"，兼容"杭州市"） */
        private String city;

        /** 高德 citycode（如"0571"） */
        private String code;
    }
}
