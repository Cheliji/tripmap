package com.tripmap.server.infra;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.InetSocketAddress;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MySQL 8 连通性冒烟测试（任务 0.5）。
 *
 * <p>本地中间件不可达时跳过而非失败，保证无 MySQL 的 CI 环境仍可跑单测；
 * 开发机已安装 MySQL 的情况下本用例必须真实通过。</p>
 */
// 占位密钥仅为加载完整 ApplicationContext（高德客户端 fail-fast 校验），本测试不发起真实调用
@SpringBootTest(properties = "tripmap.amap.api-key=test-placeholder-key")
class MySqlConnectivitySmokeTest {

    private static final String DB_HOST = "127.0.0.1";
    private static final int DB_PORT = 3306;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void checkMySqlReachable() {
        assumeTrue(portOpen(DB_HOST, DB_PORT),
                "本地 MySQL 未监听 %s:%d，跳过冒烟测试".formatted(DB_HOST, DB_PORT));
    }

    @Test
    @DisplayName("数据源可连接：SELECT 1 返回 1")
    void selectOne() {
        Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        assertEquals(1, result);
    }

    @Test
    @DisplayName("tripmap 库存在且默认字符集为 utf8mb4")
    void databaseCharsetIsUtf8mb4() {
        String charset = jdbcTemplate.queryForObject(
                "SELECT DEFAULT_CHARACTER_SET_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = 'tripmap'",
                String.class);
        assertNotNull(charset, "数据库 tripmap 不存在，请先执行 sql/000_create_database.sql");
        assertEquals("utf8mb4", charset, "tripmap 默认字符集必须是 utf8mb4");
    }

    private static boolean portOpen(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 2_000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
