package com.tripmap.server.controller;

import com.tripmap.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 脚手架冒烟接口（阶段 0 - 0.2）
 * 返回格式遵循统一返回体约定（任务 0.7 建立）
 */
@Tag(name = "脚手架", description = "阶段 0 冒烟接口")
@RestController
public class HelloController {

    @Operation(summary = "冒烟问候接口", description = "验证服务可运行，返回统一返回体包裹的问候语")
    @GetMapping("/hello")
    public Result<Map<String, String>> hello() {
        return Result.ok(Map.of("message", "Hello World, tripmap!"));
    }
}
