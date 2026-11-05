package com.tripmap.server;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * tripmap 启动类（tripmap-server 为唯一可启动/装配模块）
 */
@OpenAPIDefinition(info = @Info(title = "tripmap API", version = "0.0.1", description = "Agent 编排旅游行程规划系统接口文档"))
// 扫描范围扩展到工具层/编排层，使 tripmap-tool 等模块的 @Configuration 得以装配
//（tripmap-server 为唯一可启动/装配模块，依赖只允许自上而下，AGENTS.md 第 4 节）
@SpringBootApplication(scanBasePackages = "com.tripmap")
public class TripmapApplication {

    public static void main(String[] args) {
        SpringApplication.run(TripmapApplication.class, args);
    }
}
