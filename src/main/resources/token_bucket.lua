-- 令牌桶限流：按流逝时间补发令牌，桶容量限制突发流量
--
-- 与滑动窗口的区别（rate_limit.lua）：滑动窗口统计"最近 window 内的请求数"，
-- 语义是严格的频率上限；令牌桶以恒定速率补发令牌、桶容量允许突发——
-- 秒杀入口正是突发场景（瞬时抢购 + 平均速率可控），令牌桶语义更贴合。
--
-- KEYS[1] 桶 key（HASH：tokens=剩余令牌数（浮点），ts=上次结算时间戳（毫秒））
-- ARGV[1] 桶容量 capacity（最大突发量）
-- ARGV[2] 补充速率 rate（每秒补发的令牌数，即持续吞吐）
-- ARGV[3] 本次请求消耗的令牌数 permits（默认 1）
-- ARGV[4] 当前时间戳（毫秒）
--
-- 返回：1 = 放行；0 = 拒绝（令牌不足）
--
-- 为什么用 HASH 存 {tokens, ts} 而不是两个 key：补发计算必须同时读两个值
-- 且在同一脚本内结算写回——原子性由单脚本保证，两个 key 反而引入一致性负担。
local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local rate = tonumber(ARGV[2])
local permits = tonumber(ARGV[3])
local now = tonumber(ARGV[4])

local data = redis.call('hmget', key, 'tokens', 'ts')
local tokens = tonumber(data[1])
local ts = tonumber(data[2])
if tokens == nil then
    tokens = capacity   -- 首次访问：满桶
    ts = now
end

-- 按流逝时间补发令牌（恒定速率，封顶桶容量）
local delta = math.max(0, now - ts) / 1000
tokens = math.min(capacity, tokens + delta * rate)

local allowed = 0
if tokens >= permits then
    tokens = tokens - permits
    allowed = 1
end

redis.call('hmset', key, 'tokens', tokens, 'ts', now)
-- 过期时间：把桶重新装满所需时间 + 60s 余量，闲置自动清理
redis.call('expire', key, math.ceil(capacity / rate) + 60)
return allowed
