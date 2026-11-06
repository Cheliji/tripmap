package com.tripmap.tool.deepseek;

import lombok.Getter;
import lombok.Setter;

/**
 * DeepSeek 客户端配置（联网搜索 + LLM 共用，tripmap-poi功能设计说明书 §4.8）
 *
 * <p>api-key 一律环境变量注入（AGENTS.md 第 5 节），禁止硬编码/入库；
 * 密钥缺失时客户端构造即失败（fail-fast，与 AmapClient 同款约定）。
 * 本类零 Spring 注解，离线脚本直接 new 使用；运行时装配由上层自行绑定。</p>
 *
 * <p>含密钥字段，故用 {@code @Getter}/{@code @Setter} 而不用 {@code @Data}——
 * 避免默认 toString 打印 apiKey（AGENTS.md 第 6 节）。</p>
 */
@Getter
@Setter
public class DeepSeekProperties {

    /** DeepSeek API Key（环境变量 DEEPSEEK_API_KEY 注入） */
    private String apiKey;

    /** DeepSeek 域名（联调/打桩可覆盖，默认官方域名） */
    private String baseUrl = "https://api.deepseek.com";

    /** 对话模型（flash 档位，DeepSeek 官方模型名） */
    private String chatModel = "deepseek-chat";

    /** 连接超时（毫秒） */
    private int connectTimeoutMs = 3000;

    /** 联网搜索读取超时（毫秒，§4.8 建议 10s） */
    private int searchReadTimeoutMs = 10000;

    /** LLM 读取超时（毫秒，结构化整理单次调用上限） */
    private int chatReadTimeoutMs = 60000;

    /** 最大重试次数（指数退避，不含首次调用）。0 表示不重试 */
    private int maxRetries = 2;

    /** 重试退避基准间隔（毫秒）：第 n 次重试等待 base * 2^(n-1) + 抖动 */
    private long retryBaseDelayMs = 500;

    /** 联网搜索最小调用间隔（毫秒，§4.8 建议 ≥1 次/秒，保护配额） */
    private long searchMinIntervalMs = 1000;

    /** 联网搜索单次返回条数（官方上限 50，§4.8 取 10） */
    private int searchCount = 10;

    /** LLM 结构化整理温度（低温度保证抽取稳定） */
    private double chatTemperature = 0.2;
}
