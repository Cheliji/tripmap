package com.tripmap.poi.enrich;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripmap.common.constant.PoiCategory;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * category 规则映射：typecode → 项目内分类（§4.4，纯代码、不经 LLM，零幻觉面）
 *
 * <p><b>表即规则</b>：资源文件 {@code /mapping/category-mapping.json} 按优先级自上而下
 * 维护"前缀 → 枚举"，转换五条规则：</p>
 * <ol>
 *   <li>拆分：typecode 按 {@code |} 拆成编码集合；为空 → 规则 5；</li>
 *   <li>优先级扫描：自上而下检查编码集合中是否存在任一编码以该映射前缀开头
 *       （六位精确码在表内先于短前缀，命中即终止）；</li>
 *   <li>多值裁决：{@code 061202|140200|170200} → 文博展馆（140 优先于 06）；
 *       {@code 080501|060000} → 亲子游乐（不被商圈抢占）；</li>
 *   <li>排除项：{@code 110106} 公园内部设施不在映射表内，仅命中它视为未命中；</li>
 *   <li>未命中：返回空（调用方记 warn），raw 的 typecode 原样保留可事后重映射，不标 FAILED。</li>
 * </ol>
 *
 * <p>新增城市遇到白名单外 typecode → 资源文件加映射行，零代码改动。
 * 资源加载时校验每行枚举名合法（对齐 {@link PoiCategory}，防表与枚举漂移）。</p>
 */
public class CategoryMapping {

    private static final String DEFAULT_RESOURCE = "/mapping/category-mapping.json";

    private final List<MappingEntry> entries;

    private CategoryMapping(List<MappingEntry> entries) {
        this.entries = entries;
    }

    /** 单条映射：前缀集合 + 目标分类（一条映射一行参数化单测，§4.4 维护约定） */
    record MappingEntry(List<String> prefixes, PoiCategory category) {
    }

    /** 加载默认资源文件（classpath:/mapping/category-mapping.json） */
    public static CategoryMapping loadDefault() {
        return load(DEFAULT_RESOURCE);
    }

    /** 加载指定资源（测试可用文件路径或类路径资源） */
    public static CategoryMapping load(String resource) {
        ObjectMapper objectMapper = new ObjectMapper();
        try (InputStream in = open(resource)) {
            JsonNode root = objectMapper.readTree(in);
            if (!root.isArray()) {
                throw new IllegalStateException("category 映射资源必须是数组: " + resource);
            }
            List<MappingEntry> entries = new ArrayList<>();
            for (JsonNode node : root) {
                PoiCategory category = PoiCategory.valueOf(node.required("category").asText());
                List<String> prefixes = new ArrayList<>();
                for (JsonNode prefix : node.required("prefixes")) {
                    prefixes.add(prefix.asText());
                }
                if (prefixes.isEmpty()) {
                    throw new IllegalStateException("category 映射行 prefixes 为空: " + category);
                }
                entries.add(new MappingEntry(List.copyOf(prefixes), category));
            }
            if (entries.isEmpty()) {
                throw new IllegalStateException("category 映射资源为空: " + resource);
            }
            return new CategoryMapping(entries);
        } catch (IOException e) {
            throw new UncheckedIOException("category 映射资源加载失败: " + resource, e);
        }
    }

    private static InputStream open(String resource) {
        if (resource.startsWith("/")) {
            InputStream in = CategoryMapping.class.getResourceAsStream(resource);
            if (in == null) {
                throw new IllegalStateException("映射资源不存在: classpath:" + resource);
            }
            return in;
        }
        try {
            return java.nio.file.Files.newInputStream(java.nio.file.Path.of(resource));
        } catch (IOException e) {
            throw new UncheckedIOException("映射文件不可读: " + resource, e);
        }
    }

    /**
     * typecode → 分类（§4.4 转换规则）。
     *
     * @param typecode 高德分类编码，可能多值（竖线分隔）
     * @return 命中的分类；空白/未命中返回空（调用方记 warn，不标 FAILED）
     */
    public Optional<PoiCategory> map(String typecode) {
        if (!StringUtils.hasText(typecode)) {
            return Optional.empty();
        }
        List<String> codes = new ArrayList<>();
        for (String part : typecode.split("\\|")) {
            String code = part.trim();
            if (!code.isEmpty()) {
                codes.add(code);
            }
        }
        if (codes.isEmpty()) {
            return Optional.empty();
        }
        // 规则 2/3：按表内顺序优先级扫描，命中即终止
        for (MappingEntry entry : entries) {
            for (String prefix : entry.prefixes()) {
                for (String code : codes) {
                    if (code.startsWith(prefix)) {
                        return Optional.of(entry.category());
                    }
                }
            }
        }
        return Optional.empty();
    }
}
