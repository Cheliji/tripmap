package com.tripmap.common.dto.tool.search;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 联网搜索结果条目。
 *
 * <p>{@code url} 即来源凭证：攻略节点执行"无来源事实句删除"（2.7.2 后处理约定）
 * 的唯一依据，缺失 url 的条目视为无效结果。</p>
 *
 * @param title   标题
 * @param url     来源 URL（强制非空）
 * @param snippet 摘要/正文片段
 */
@Schema(description = "联网搜索结果条目")
public record SearchHit(@Schema(description = "标题") String title,
                        @Schema(description = "来源 URL（无来源即无效条目）") String url,
                        @Schema(description = "摘要/正文片段") String snippet) {
}
