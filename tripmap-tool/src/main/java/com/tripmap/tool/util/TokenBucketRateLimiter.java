package com.tripmap.tool.util;

import java.util.concurrent.locks.LockSupport;

/**
 * 令牌桶限流器（任务 1.1.2）
 *
 * <p>以固定速率（QPS）补充令牌，桶容量 = 速率（不允许突发超过 QPS 的请求尖峰）。
 * 获取令牌不足时按精确缺额等待，多线程并发获取时整体速率被钳制在 QPS 以内。
 * 供高德等受配额约束的外部 API 客户端复用。</p>
 *
 * <p>实现要点：refill 按纳秒 elapsed * rate 计算，避免独立补桶线程（无后台调度开销）；\n * 等待发生在锁外（{@link LockSupport#parkNanos}），不阻塞其他线程补充令牌。</p>
 */
public final class TokenBucketRateLimiter {

    /** 桶容量（令牌数），即允许的瞬时并发上限 */
    private final double capacity;

    /** 每秒补充令牌数 */
    private final double refillPerSecond;

    /** 当前令牌数 */
    private double tokens;

    /** 上次补充令牌的时间戳（System.nanoTime） */
    private long lastRefillNanos;

    /**
     * @param qps 每秒允许通过的最大请求数，必须为正数
     */
    public TokenBucketRateLimiter(double qps) {
        if (qps <= 0) {
            throw new IllegalArgumentException("qps 必须为正数: " + qps);
        }
        this.capacity = qps;
        this.refillPerSecond = qps;
        this.tokens = qps;
        this.lastRefillNanos = System.nanoTime();
    }

    /**
     * 获取一个令牌（不足时精确等待），返回实际等待毫秒数。
     * 多线程安全。
     */
    public double acquire() {
        long start = System.nanoTime();
        for (;;) {
            long waitNanos;
            synchronized (this) {
                refill();
                if (tokens >= 1.0) {
                    tokens -= 1.0;
                    return (System.nanoTime() - start) / 1_000_000.0;
                }
                // 缺额令牌数换算为等待时长
                waitNanos = (long) ((1.0 - tokens) / refillPerSecond * 1_000_000_000L);
            }
            // 锁外等待，避免阻塞其他线程补充令牌
            LockSupport.parkNanos(waitNanos);
        }
    }

    /** 按流逝时间补充令牌，封顶桶容量 */
    private void refill() {
        long now = System.nanoTime();
        double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
        if (elapsedSeconds > 0) {
            tokens = Math.min(capacity, tokens + elapsedSeconds * refillPerSecond);
            lastRefillNanos = now;
        }
    }
}
