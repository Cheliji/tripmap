package com.tripmap.common.result;

/**
 * 统一错误码（任务 0.7 建立，AGENTS.md 第 6 节约定）
 *
 * 码段约定（6 位数字，码段首位即失败大类，供前端拦截器快速判断）：
 * <ul>
 *   <li>0      —— 成功</li>
 *   <li>1xxxxx —— 客户端/参数错误</li>
 *   <li>2xxxxx —— 资源错误</li>
 *   <li>3xxxxx —— 业务规则（预期内拒绝，如意图校验拒绝 R1）</li>
 *   <li>4xxxxx —— 外部服务（高德/天气/搜索/LLM，可重试）</li>
 *   <li>5xxxxx —— 系统异常（兜底，对外脱敏）</li>
 * </ul>
 * 枚举天然保证码值唯一；新增码值必须落在既有码段内，不得跳段。
 */
public enum ResultCode {

    /** 成功 */
    SUCCESS(0, "ok"),

    /** 参数校验失败（JSR-303 校验不通过） */
    PARAM_VALIDATION_FAILED(100001, "参数校验失败"),
    /** 缺少必填参数 */
    PARAM_MISSING(100002, "缺少必填参数"),
    /** 参数格式非法（如请求体 JSON 解析失败） */
    PARAM_ILLEGAL(100003, "参数格式非法"),
    /** 请求方式不支持（如 GET 接口收到 POST） */
    METHOD_NOT_SUPPORTED(100004, "请求方式不支持"),

    /** 请求的资源不存在（路径无对应处理器） */
    RESOURCE_NOT_FOUND(200001, "请求的资源不存在"),

    /** 城市不在白名单（意图校验拒绝分支 R1 复用此码） */
    CITY_NOT_SUPPORTED(300001, "目的地暂不支持，当前仅支持杭州"),
    /** 行程规划无可行解 */
    PLAN_INFEASIBLE(300002, "行程规划不可行，请调整出行条件后重试"),

    /** 高德开放平台调用失败 */
    EXTERNAL_AMAP(400001, "地图服务暂时不可用，请稍后重试"),
    /** 天气 API 调用失败 */
    EXTERNAL_WEATHER(400002, "天气服务暂时不可用"),
    /** 搜索 API 调用失败 */
    EXTERNAL_SEARCH(400003, "搜索服务暂时不可用，请稍后重试"),
    /** LLM 调用失败 */
    EXTERNAL_LLM(400004, "智能服务暂时不可用，请稍后重试"),

    /** 未捕获异常兜底码（对外固定脱敏文案，堆栈只进日志） */
    SYSTEM_ERROR(500001, "系统繁忙，请稍后重试");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
