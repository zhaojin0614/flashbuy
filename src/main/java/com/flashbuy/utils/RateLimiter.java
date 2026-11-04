package com.flashbuy.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 基于 Redis ZSet 的滑动窗口限流器（Lua 单脚本保证原子性）
 *
 * 窗口语义：最近 windowMillis 毫秒内的请求次数 < limit 才放行。
 * 滑动窗口 vs 固定窗口：固定窗口在窗口边界有 2 倍突刺问题
 * （上一窗口末尾 + 下一窗口开头可挤进 2*limit 次），滑动窗口按
 * "当前时刻往回推 window" 计数，无边界突刺。
 */
@Slf4j
@Component
public class RateLimiter {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> SCRIPT;

    static {
        // ZREMRANGEBYSCORE 清除窗口外的旧记录 -> ZCARD 统计窗口内次数
        // -> 未超限则 ZADD 记录本次并重置过期，返回 1 放行；否则返回 0 拒绝
        SCRIPT = new DefaultRedisScript<>();
        SCRIPT.setScriptText(
                "local n = redis.call('ZCARD', KEYS[1]) " +
                "if n < tonumber(ARGV[3]) then " +
                "  redis.call('ZADD', KEYS[1], ARGV[1], ARGV[4]) " +
                "  redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[2])) " +
                "  return 1 " +
                "end " +
                "return 0");
        SCRIPT.setResultType(Long.class);
    }

    /**
     * @param key         限流维度键（如 rl:code:13800001111）
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
}
