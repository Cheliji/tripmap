package com.tripmap.server.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 脚手架冒烟接口（阶段 0 - 0.2）
 * 注：统一返回体在任务 0.7 建立后替换此处返回格式
 */
@RestController
public class HelloController {

    @GetMapping("/hello")
    public Map<String, String> hello() {
        return Map.of("message", "Hello World, tripmap!");
    }
}
