package com.tripmap.tool.search.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 联网搜索结果条目（任务 2.7，强制带来源）。
 *
 * <p>{@code index} 为<b>从 1 开始</b>的来源序号，即攻略节点输出中引用标记
 * {@code [n]} 的 n——"无来源事实句删除"约定（TASKS 2.7.2）按此序号核对引用合法性。</p>
 *
 * <p>{@code url} 是溯源红线：工具层丢弃无 url 条目（无法溯源的素材不进入
 * 攻略上下文，AGENTS.md 第 1 节原则 2）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchSourceItem {

    /** 来源序号（从 1 开始，引用标记 [n] 的 n） */
    private int index;

    /** 结果标题 */
    private String title;

    /** 来源 URL（溯源凭据，强制非空；空条目已被工具层丢弃） */
    private String url;

    /** 结果摘要（官方 rerank 摘要，可空） */
    private String summary;

    /** 网页正文（need_content=true 时返回，可空） */
    private String content;

    /** 官方重排打分（缺省 0.0） */
    private double rerankScore;
}
