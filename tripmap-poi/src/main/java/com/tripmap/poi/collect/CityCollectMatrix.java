package com.tripmap.poi.collect;

import java.util.List;

/**
 * 单城市采集矩阵（任务 1.3.1，设计说明书 §3.2）
 *
 * <p>采集范围 = 类型白名单 × 城市分区 二维矩阵（{@link #combos()} 展开为查询组合），
 * 资源文件维护于 {@code resources/keywords/collect-matrix.json}——新增城市 = 追加 JSON 对象，零代码改动。</p>
 *
 * @param city       城市名（对应高德 city 参数）
 * @param cityAdcode 城市 adcode（文档留痕；请求按分区 adcode 逐格下发）
 * @param districts  城市分区格列表
 * @param categories 分类白名单（含 EXCLUDE 留痕项，展开时过滤）
 */
public record CityCollectMatrix(String city, String cityAdcode,
                                List<DistrictGrid> districts, List<CategoryItem> categories) {

    public CityCollectMatrix {
        if (city == null || city.isBlank()) {
            throw new IllegalArgumentException("城市名不能为空");
        }
        if (cityAdcode == null || !cityAdcode.matches("\\d{6}")) {
            throw new IllegalArgumentException("城市 adcode 必须为 6 位数字: " + cityAdcode);
        }
        districts = districts == null ? List.of() : List.copyOf(districts);
        categories = categories == null ? List.of() : List.copyOf(categories);
        if (districts.isEmpty() || categories.isEmpty()) {
            throw new IllegalArgumentException("城市 " + city + " 的分区或分类矩阵为空");
        }
    }

    /**
     * 矩阵展开：分区 × 采集类分类（EXCLUDE 过滤后）的全部查询组合。
     */
    public List<Combo> combos() {
        return districts.stream()
                .flatMap(district -> categories.stream()
                        .filter(CategoryItem::collect)
                        .map(category -> new Combo(this, district, category)))
                .toList();
    }

    /**
     * 一个（分区 × 分类）查询组合（§3.3 的最小采集单元）。
     */
    public record Combo(CityCollectMatrix matrix, DistrictGrid district, CategoryItem category) {

        /**
         * 组合 → place/text 查询：类型用 types 编码，商圈类用 keywords；
         * region 下发分区 adcode 把大类候选量切进翻页深度（§3.3）。
         */
        public AmapPoiSearchQuery toQuery() {
            return new AmapPoiSearchQuery(
                    category.keywords(), category.typecode(),
                    matrix.city(), true, district.adcode(),
                    AmapPoiSearchQuery.MAX_PAGE_SIZE, AmapPoiSearchQuery.DEFAULT_MAX_PAGES);
        }

        /** 命中标记：记入 collect_keywords（types 记编码，keywords 记词面，§3.4） */
        public String hitTag() {
            return category.typecode() != null ? category.typecode() : category.keywords();
        }
    }
}
