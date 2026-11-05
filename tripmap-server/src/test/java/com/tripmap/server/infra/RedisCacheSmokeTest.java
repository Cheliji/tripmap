package com.tripmap.server.infra;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Redis 缓存读写冒烟测试（任务 0.6）。
 *
 * <p>本地中间件不可达时跳过而非失败，保证无 Redis 的 CI 环境仍可跑单测；
 * 开发机已启动 Redis 的情况下本用例必须真实通过。</p>
 */
@SpringBootTest
class RedisCacheSmokeTest {

    private static final String REDIS_HOST = "127.0.0.1";
    private static final int REDIS_PORT = 6379;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeAll
    static void checkRedisReachable() {
        assumeTrue(portOpen(REDIS_HOST, REDIS_PORT),
                "本地 Redis 未监听 %s:%d，跳过冒烟测试".formatted(REDIS_HOST, REDIS_PORT));
    }

    @Test
    @DisplayName("缓存写入后可读回，TTL 与删除生效")
    void cacheReadWrite() {
        String key = "tripmap:smoke:" + UUID.randomUUID();
        String value = "smoke-test-value";

        try {
            // 写入并设置短 TTL
            redisTemplate.opsForValue().set(key, value, Duration.ofSeconds(60));

            // 读回：验证连通与序列化往返
            assertEquals(value, redisTemplate.opsForValue().get(key));

            // TTL 生效：剩余存活时间在 0～60 秒之间
            Long ttl = redisTemplate.getExpire(key);
            assertNotNull(ttl);
            assertTrue(ttl > 0 && ttl <= 60, "TTL 应在 0~60 秒之间，实际：" + ttl);

            // 删除后不可读
            redisTemplate.delete(key);
            assertNull(redisTemplate.opsForValue().get(key));
        } finally {
            redisTemplate.delete(key);
        }
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
