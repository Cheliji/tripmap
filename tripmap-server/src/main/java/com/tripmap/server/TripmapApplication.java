package com.tripmap.server;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * tripmap 启动类（tripmap-server 为唯一可启动/装配模块）
 */
@OpenAPIDefinition(info = @Info(title = "tripmap API", version = "0.0.1", description = "Agent 编排旅游行程规划系统接口文档"))
@SpringBootApplication
public class TripmapApplication {

    public static void main(String[] args) {
        SpringApplication.run(TripmapApplication.class, args);
    }
}
