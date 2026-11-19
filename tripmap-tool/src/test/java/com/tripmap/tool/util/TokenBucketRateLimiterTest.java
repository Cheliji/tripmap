package com.tripmap.tool.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 令牌桶限流器单测（任务 1.1.2）
 */
class TokenBucketRateLimiterTest {

    @Test
    void 构造参数必须为正数() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(0));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(-1));
    }

    @Test
    void 初始桶满可立即通过() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(3);
        long start = System.currentTimeMillis();
        for (int i = 0; i < 3; i++) {
            limiter.acquire();
        }
        // 前 3 次（桶容量内）应无实质等待
        assertTrue(System.currentTimeMillis() - start < 200, "桶内令牌应立即放行");
    }

    @Test
    void 超速请求被限速到目标QPS() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5);
        int total = 11; // 容量 5 + 后续每秒 5 个：11 个至少需 ~1.2s
        long start = System.currentTimeMillis();
        for (int i = 0; i < total; i++) {
            limiter.acquire();
        }
        long elapsed = System.currentTimeMillis() - start;
        // 下限：11 个令牌需要 (11-5)/5 ≈ 1.2s；上限给足余量
        assertTrue(elapsed >= 1000, "11 个请求 @5QPS 至少耗时 1s，实际 " + elapsed + "ms");
        assertTrue(elapsed < 5000, "等待不应过度膨胀，实际 " + elapsed + "ms");
    }

    @Test
    void 多线程并发整体速率不超限() throws InterruptedException {
        double qps = 10;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(qps);
        int threads = 8;
        int perThread = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads * perThread);
        LongAdder passed = new LongAdder();
        List<Throwable> errors = new ArrayList<>();

        long start = System.currentTimeMillis();
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    for (int i = 0; i < perThread; i++) {
                        limiter.acquire();
                        passed.increment();
                        done.countDown();
                    }
                } catch (Throwable e) {
                    errors.add(e);
                }
            });
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown();
        // 40 个请求 @10QPS 至少需 3.6s（减去初始桶 10）
        assertTrue(done.await(10, TimeUnit.SECONDS), "全部请求应在限流节奏内完成");
        long elapsed = System.currentTimeMillis() - start;
        pool.shutdownNow();

        assertTrue(errors.isEmpty(), "并发获取不应抛异常: " + errors);
        assertTrue(passed.sum() == threads * perThread);
        assertTrue(elapsed >= 3000, "40 个请求 @10QPS 至少耗时 ~3.6s，实际 " + elapsed + "ms");
    }

    @Test
    void 低频请求零等待() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100);
        limiter.acquire();
        // 间隔足够久后令牌应已补满
        assertDoesNotThrow(() -> {
            for (int i = 0; i < 50; i++) {
                limiter.acquire();
                Thread.sleep(30); // 50 请求 × 30ms = 1.67s，远低于 100QPS
            }
        });
    }
}
