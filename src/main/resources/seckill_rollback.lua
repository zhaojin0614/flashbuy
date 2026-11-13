-- 秒杀补偿脚本：MQ 发送失败时回滚 seckill.lua 的预扣（库存 +1、撤销资格）
--
-- 为什么必须是一个脚本：两条命令要么都执行要么都不执行。
-- 若拆开执行，回滚中途连接断开会留下"库存已回补但资格还在"的半回滚状态，
-- 该用户此后永远无法下单（SISMEMBER 命中返回 2）。
--
-- KEYS：无。key 由 ARGV 在内部拼接，与 seckill.lua 同风格
--       （Redis Cluster 下需改为 KEYS 传入并用 {productId} 哈希标签保证同 slot）
-- ARGV[1] 商品 id
-- ARGV[2] 用户 id
--
-- 返回：0 = 回滚完成
local productId = ARGV[1]
local userId = ARGV[2]

local stockKey = 'seckill:stock:' .. productId
local orderKey = 'seckill:order:' .. productId

redis.call('incrby', stockKey, 1)
redis.call('srem', orderKey, userId)
return 0
