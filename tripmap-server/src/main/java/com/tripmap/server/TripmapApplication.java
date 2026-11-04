package com.tripmap.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * tripmap 启动类（tripmap-server 为唯一可启动/装配模块）
 */
@SpringBootApplication
public class TripmapApplication {

    public static void main(String[] args) {
        SpringApplication.run(TripmapApplication.class, args);
    }
}
