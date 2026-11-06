package com.tripmap.poi.enrich;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Optional;

/**
 * district_adcode 静态映射：adname（区县名）→ adcode（GB/T 2260，§4.3）
 *
 * <p>place/text 响应无 adcode 仅 adname，且实测杭州快照 adname 全部为市辖区标准名，
 * 故不引入逆地理编码，资源文件维护静态映射（表即规则）。</p>
 *
 * <p>杭州首版映射表（项目方 2026-11-05 提供）校对注：</p>
 * <ul>
 *   <li>"拱野区"按"拱墅区"录入（330105）；</li>
 *   <li>下城区/江干区 2021 年撤并后保留旧码，兼容历史数据；</li>
 *   <li>富阳/临安已撤市设区（现码 330111/330112），保留旧县级市码兼容新旧区名并存；</li>
 *   <li>城市切换 = 本资源文件追加区名表，零代码改动（M5 复用）。</li>
 * </ul>
 *
 * <p>未命中：调用方记 warn 且该字段留空，行仍可判 SUCCESS（§4.2，不标 FAILED）。</p>
 */
public class DistrictAdcodeMapping {

    private static final String DEFAULT_RESOURCE = "/mapping/district-adcode.json";

    private final Map<String, String> nameToAdcode;

    private DistrictAdcodeMapping(Map<String, String> nameToAdcode) {
        this.nameToAdcode = nameToAdcode;
    }

    /** 加载默认资源文件（classpath:/mapping/district-adcode.json） */
    public static DistrictAdcodeMapping loadDefault() {
        return load(DEFAULT_RESOURCE);
    }

    /** 加载指定资源（测试可用文件路径或类路径资源） */
    public static DistrictAdcodeMapping load(String resource) {
        ObjectMapper objectMapper = new ObjectMapper();
        try (InputStream in = open(resource)) {
            Map<String, String> map = objectMapper.readValue(in,
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, String.class));
            return new DistrictAdcodeMapping(Map.copyOf(map));
        } catch (IOException e) {
            throw new UncheckedIOException("district-adcode 映射资源加载失败: " + resource, e);
        }
    }

    private static InputStream open(String resource) {
        if (resource.startsWith("/")) {
            InputStream in = DistrictAdcodeMapping.class.getResourceAsStream(resource);
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

    /** 查找区县 adcode；adname 空白或未命中返回空（调用方记 warn） */
    public Optional<String> lookup(String adname) {
        if (!StringUtils.hasText(adname)) {
            return Optional.empty();
        }
        return Optional.ofNullable(nameToAdcode.get(adname.trim()));
    }
}
