package com.tripmap.poi.collect;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 采集矩阵加载器（任务 1.3.1，设计说明书 §3.1/§3.2）
 *
 * <p>从类路径资源 {@code keywords/collect-matrix.json} 读取全部分市矩阵，
 * 按城市名查找。新增城市 = 在 JSON 中追加城市对象，零代码改动；
 * 查找失败给出"补充分区与类型矩阵"的指引（城市切换不做白名单校验，§3.1）。</p>
 */
public class CollectMatrixLoader {

    /** 默认矩阵资源路径（与 collect 包 package-info 约定一致） */
    public static final String DEFAULT_RESOURCE = "keywords/collect-matrix.json";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 加载默认资源中指定城市的采集矩阵。
     *
     * @param city 城市名（如"杭州"）
     * @return 该城市的分区 × 分类矩阵
     * @throws IllegalArgumentException 矩阵未定义该城市
     * @throws IllegalStateException    资源不存在或解析失败
     */
    public CityCollectMatrix loadDefault(String city) {
        return load(DEFAULT_RESOURCE, city);
    }

    /**
     * 从指定类路径资源加载城市矩阵（独立方法便于测试注入资源）。
     */
    public CityCollectMatrix load(String resourcePath, String city) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("采集矩阵资源不存在: classpath:" + resourcePath);
            }
            MatrixFile file = objectMapper.readValue(in, MatrixFile.class);
            return file.cities().stream()
                    .filter(c -> c.city().equals(city))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "采集矩阵未定义城市 [" + city + "]：请在 " + resourcePath
                                    + " 中补充该城市的分区与类型矩阵（设计说明书 §3.1：城市切换 = 改资源文件，无白名单校验）"));
        } catch (IOException e) {
            throw new IllegalStateException("采集矩阵解析失败: classpath:" + resourcePath, e);
        }
    }

    /** 矩阵资源顶层结构 */
    private record MatrixFile(List<CityCollectMatrix> cities) {
    }
}
