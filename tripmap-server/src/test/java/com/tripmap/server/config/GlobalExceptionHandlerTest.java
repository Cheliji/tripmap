package com.tripmap.server.config;

import com.tripmap.common.exception.BizException;
import com.tripmap.common.exception.ExternalApiException;
import com.tripmap.common.result.Result;
import com.tripmap.common.result.ResultCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全局异常处理测试（任务 0.7）
 *
 * 使用 standalone MockMvc 隔离装配，只验证异常 → 统一返回体的映射与脱敏行为。
 */
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ErrorStubController())
                // 未知路径抛出 NoHandlerFoundException，交由全局异常处理（对齐 application.yml 配置）
                .addDispatcherServletCustomizer(ds -> ds.setThrowExceptionIfNoHandlerFound(true))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void 业务异常应按码原样返回() throws Exception {
        mockMvc.perform(get("/stub/biz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.CITY_NOT_SUPPORTED.getCode()))
                .andExpect(jsonPath("$.message").value(ResultCode.CITY_NOT_SUPPORTED.getMessage()))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void 外部服务异常应返回外部码段() throws Exception {
        mockMvc.perform(get("/stub/external"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.EXTERNAL_AMAP.getCode()))
                .andExpect(jsonPath("$.message").value(ResultCode.EXTERNAL_AMAP.getMessage()));
    }

    @Test
    void 未捕获异常应兜底500001且消息脱敏() throws Exception {
        mockMvc.perform(get("/stub/unknown"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SYSTEM_ERROR.getCode()))
                .andExpect(jsonPath("$.message").value(ResultCode.SYSTEM_ERROR.getMessage()));
    }

    @Test
    void 缺少必填参数应返回100002并指明参数名() throws Exception {
        mockMvc.perform(get("/stub/param"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_MISSING.getCode()))
                .andExpect(jsonPath("$.message").value(ResultCode.PARAM_MISSING.getMessage() + ": city"));
    }

    @Test
    void 请求体解析失败应返回100003() throws Exception {
        mockMvc.perform(post("/stub/json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not-a-json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ILLEGAL.getCode()));
    }

    @Test
    void 未知路径应返回200001() throws Exception {
        mockMvc.perform(get("/stub/no-such-path"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.RESOURCE_NOT_FOUND.getCode()));
    }

    /** 触发各类异常的桩接口 */
    @RestController
    static class ErrorStubController {

        @GetMapping("/stub/biz")
        public Result<Void> biz() {
            throw new BizException(ResultCode.CITY_NOT_SUPPORTED);
        }

        @GetMapping("/stub/external")
        public Result<Void> external() {
            throw new ExternalApiException(ResultCode.EXTERNAL_AMAP, "高德", true);
        }

        @GetMapping("/stub/unknown")
        public Result<Void> unknown() {
            throw new IllegalStateException("内部实现细节，不应透出");
        }

        @GetMapping("/stub/param")
        public Result<Void> param(@RequestParam("city") String city) {
            return Result.ok();
        }

        @PostMapping("/stub/json")
        public Result<Void> json(@RequestBody(required = false) Map<String, Object> body) {
            return Result.ok();
        }
    }
}
