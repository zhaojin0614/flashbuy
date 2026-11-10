-- 秒杀原子脚本：库存判断 + 一人一单判断 + 预扣库存 + 写资格 Set + 发送 Stream 消息
--
-- KEYS：无。当前脚本根据 ARGV[1] 在内部拼接 key：
--       库存 key = seckill:stock:{productId}
--       资格 key = seckill:order:{productId}
--       单机 Redis 可用；Redis Cluster 下需改为通过 KEYS 传入，并用 {productId} 哈希标签保证同 slot。
-- ARGV[1] 商品 id
-- ARGV[2] 用户 id
-- ARGV[3] 订单 id（RedisIdWorker 生成的全局唯一 id）
--
-- 返回：0 = 抢购成功；1 = 库存不足；2 = 重复下单
--
-- 1.参数列表
-- 1.1.商品id
local productId = ARGV[1]
-- 1.2.用户id
local userId = ARGV[2]
-- 1.3.订单id
local orderId = ARGV[3]

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
-- 3.2.判断用户是否下单 SISMEMBER orderKey userId
if(redis.call('sismember', orderKey, userId) == 1) then
    -- 3.3.存在，说明是重复下单，返回2
    return 2
end
-- 3.4.扣库存 incrby stockKey -1
redis.call('incrby', stockKey, -1)
-- 3.5.下单（保存用户）sadd orderKey userId
redis.call('sadd', orderKey, userId)
-- 3.6.发送消息到队列中， XADD stream.orders * k1 v1 k2 v2 ...
redis.call('xadd', 'stream.orders', '*', 'userId', userId, 'productId', productId, 'id', orderId)
return 0
