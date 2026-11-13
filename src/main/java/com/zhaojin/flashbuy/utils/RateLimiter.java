package com.zhaojin.flashbuy.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 基于 Redis 的限流器（Lua 单脚本保证原子性），两种语义：
 * 1.滑动窗口（ZSet，脚本 rate_limit.lua）：最近 windowMillis 毫秒内请求数 < limit
 *   才放行——严格频率上限，用于验证码/登录；固定窗口在边界有 2 倍突刺
 *   （上一窗口末尾 + 下一窗口开头可挤进 2*limit 次），滑动窗口按当前时刻回推计数无此问题
 * 2.令牌桶（HASH，脚本 token_bucket.lua）：恒定速率补发令牌、桶容量允许突发——
 *   秒杀入口（瞬时抢购 + 平均速率可控），突发语义贴合业务
 */
@Slf4j
@Component
public class RateLimiter {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> SCRIPT;

    static {
        // 清窗 -> 计数 -> 未超限则记录本次并设置过期，脚本见 rate_limit.lua
        SCRIPT = new DefaultRedisScript<>();
        SCRIPT.setLocation(new ClassPathResource("rate_limit.lua"));
        SCRIPT.setResultType(Long.class);
    }

    private static final DefaultRedisScript<Long> TOKEN_BUCKET_SCRIPT;

    static {
        // 按流逝时间补发令牌 -> 余额充足则扣减并放行，脚本见 token_bucket.lua
        TOKEN_BUCKET_SCRIPT = new DefaultRedisScript<>();
        TOKEN_BUCKET_SCRIPT.setLocation(new ClassPathResource("token_bucket.lua"));
        TOKEN_BUCKET_SCRIPT.setResultType(Long.class);
    }

    /**
     * 滑动窗口限流
     *
     * @param key          限流维度键（如 rl:code:13800001111）
     * @param windowMillis 窗口时长（毫秒）
     * @param limit        窗口内允许的最大次数
     * @return true=放行 false=拒绝
     */
    public boolean isAllowed(String key, long windowMillis, long limit) {
        long now = System.currentTimeMillis();
        List<String> args = Arrays.asList(
                String.valueOf(now),                 // ARGV[1] 当前时间戳（score）
                String.valueOf(windowMillis),        // ARGV[2] 窗口（PEXPIRE）
                String.valueOf(limit),               // ARGV[3] 上限
                UUID.randomUUID().toString());       // ARGV[4] 本次请求唯一成员
        Long allow = stringRedisTemplate.execute(SCRIPT, Collections.singletonList(key), args.toArray());
        return allow != null && allow == 1L;
    }

    /**
     * 令牌桶限流（突发允许，持续速率受限）
     *
     * @param key         桶键（如 rl:seckill:user:123）
     * @param capacity    桶容量（最大突发量）
     * @param refillPerSec 每秒补发令牌数（持续吞吐）
     * @param permits     本次请求消耗的令牌数（通常 1）
     * @return true=放行 false=拒绝
     */
    public boolean tryAcquire(String key, long capacity, double refillPerSec, int permits) {
        long now = System.currentTimeMillis();
        List<String> args = Arrays.asList(
                String.valueOf(capacity),            // ARGV[1] 桶容量
                String.valueOf(refillPerSec),        // ARGV[2] 补充速率
                String.valueOf(permits),             // ARGV[3] 本次令牌数
                String.valueOf(now));                // ARGV[4] 当前时间戳（毫秒）
        Long allow = stringRedisTemplate.execute(TOKEN_BUCKET_SCRIPT, Collections.singletonList(key), args.toArray());
        return allow != null && allow == 1L;
    }
}
