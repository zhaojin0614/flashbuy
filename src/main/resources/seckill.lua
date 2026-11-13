-- 秒杀原子脚本：库存判断 + 一人一单判断 + 预扣库存 + 写资格 Set
--
-- P3 改造说明：消息投递已移交 RocketMQ。本脚本只负责"资格与预扣"的原子性，
-- 返回 0 后由 Java 侧同步发送 MQ；发送失败走 seckill_rollback.lua 原子补偿回滚。
-- （为什么不再用 XADD 发 Redis Stream：Stream 的消费恢复/重试/死信都要手工治理，
--   RocketMQ 原生提供重试队列与死信，见改造记录第 32/34 章）
--
-- KEYS：无。当前脚本根据 ARGV[1] 在内部拼接 key：
--       库存 key = seckill:stock:{productId}
--       资格 key = seckill:order:{productId}
--       单机 Redis 可用；Redis Cluster 下需改为通过 KEYS 传入，并用 {productId} 哈希标签保证同 slot。
-- ARGV[1] 商品 id
-- ARGV[2] 用户 id
--
-- 返回：0 = 抢购成功（资格已锁定，等待 MQ 异步落库）；1 = 库存不足；2 = 重复下单
--
-- 1.参数列表
-- 1.1.商品id
local productId = ARGV[1]
-- 1.2.用户id
local userId = ARGV[2]

-- 2.数据key
-- 2.1.库存key
local stockKey = 'seckill:stock:' .. productId
-- 2.2.订单key
local orderKey = 'seckill:order:' .. productId

-- 3.脚本业务
-- 3.1.判断库存是否充足 get stockKey
if(tonumber(redis.call('get', stockKey)) <= 0) then
    -- 3.2.库存不足，返回1
    return 1
end
-- 3.3.判断用户是否下单 SISMEMBER orderKey userId
if(redis.call('sismember', orderKey, userId) == 1) then
    -- 3.4.存在，说明是重复下单，返回2
    return 2
end
-- 3.5.扣库存 incrby stockKey -1
redis.call('incrby', stockKey, -1)
-- 3.6.锁定资格（保存用户）sadd orderKey userId
redis.call('sadd', orderKey, userId)
return 0
