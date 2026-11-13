package com.zhaojin.flashbuy.listener;

import cn.hutool.json.JSONUtil;
import com.zhaojin.flashbuy.dto.SeckillMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.Message;

import jakarta.annotation.Resource;
import java.util.Collections;

import static com.zhaojin.flashbuy.utils.RedisConstants.SECKILL_ORDER_KEY;

/**
 * 秒杀事务消息监听器：把"Lua 预扣"作为事务消息的本地事务执行（任务四）。
 *
 * 事务时序：半消息 → executeLocalTransaction（Lua 预扣）→ COMMIT/ROLLBACK。
 * - Lua 返回 0（资格锁定成功）→ COMMIT：半消息转正式，消费链路照常落库
 * - Lua 返回 1/2（库存不足/重复下单）→ ROLLBACK：broker 丢弃半消息，无消息投出
 * - Lua 执行异常（结果未知，可能已执行）→ UNKNOW：等 broker 回查
 * - 回查证据：资格 Set seckill:order:{productId} 是否含该用户——Lua 是原子的，
 *   SISMEMBER 为真即代表"预扣已完整执行"，据此 COMMIT 补投消息。
 *
 * 由此消除任务一补偿方案的残余窗口：补偿本身失败（Redis 不可用）导致的库存泄漏。
 */
@Slf4j
@RocketMQTransactionListener
public class SeckillTransactionListener implements RocketMQLocalTransactionListener {

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        SeckillMessage payload = parse(msg);
        int[] holder = (int[]) arg;
        try {
            // 本地事务 = Lua 原子预扣（资格判断 + 扣库存 + 写资格 Set）
            Long r = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(),
                    payload.getProductId().toString(), payload.getUserId().toString());
            int code = r.intValue();
            if (holder != null) {
                holder[0] = code;   // 把 Lua 返回码带给请求线程，用于组装用户响应
            }
            log.info("事务本地执行（Lua 预扣）productId={} userId={} code={}",
                    payload.getProductId(), payload.getUserId(), code);
            return code == 0 ? RocketMQLocalTransactionState.COMMIT
                    : RocketMQLocalTransactionState.ROLLBACK;
        } catch (Exception e) {
            // Lua 可能已执行（如读结果超时），状态未知——交给回查按资格 Set 判定
            log.error("事务本地执行异常，等待回查判定 productId={} userId={}",
                    payload.getProductId(), payload.getUserId(), e);
            return RocketMQLocalTransactionState.UNKNOWN;
        }
    }

    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message msg) {
        SeckillMessage payload = parse(msg);
        // 回查证据：资格 Set 是 Lua 原子执行的产物，含该用户 = 预扣已完整执行 = 消息必须投出
        boolean qualified = Boolean.TRUE.equals(stringRedisTemplate.opsForSet().isMember(
                SECKILL_ORDER_KEY + payload.getProductId(), payload.getUserId().toString()));
        log.warn("事务回查 productId={} userId={} qualified={}",
                payload.getProductId(), payload.getUserId(), qualified);
        return qualified ? RocketMQLocalTransactionState.COMMIT
                : RocketMQLocalTransactionState.ROLLBACK;
    }

    /**
     * payload 形态兼容：producer 侧适配器（RocketMQUtil.convertToSpringMessage）
     * 用 message.getBody() 构造 spring Message——payload 是 byte[]（JSON 体）；
     * 回查路径同样从 broker 还原为 byte[]。两者都按 UTF-8 JSON 反序列化。
     */
    private SeckillMessage parse(Message msg) {
        Object payload = msg.getPayload();
        if (payload instanceof SeckillMessage) {
            return (SeckillMessage) payload;
        }
        String json = payload instanceof byte[]
                ? new String((byte[]) payload, java.nio.charset.StandardCharsets.UTF_8)
                : String.valueOf(payload);
        return JSONUtil.toBean(json, SeckillMessage.class);
    }
}
