-- 滑动窗口限流：窗口内请求数小于 limit 才放行
-- KEYS[1] 限流 key（如 rl:login:13800001111）
-- ARGV[1] 当前时间戳（毫秒）
-- ARGV[2] 窗口长度（毫秒）
-- ARGV[3] 窗口内允许的最大次数
-- ARGV[4] 本次请求唯一标识（UUID，防止同一毫秒内成员被 ZSet 去重）

local now = tonumber(ARGV[1])
local window = tonumber(ARGV[2])

-- 先清掉窗口外的旧记录，否则 ZCARD 会把历史请求一起算进来
redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, now - window)

local n = redis.call('ZCARD', KEYS[1])
if n < tonumber(ARGV[3]) then
    redis.call('ZADD', KEYS[1], now, ARGV[4])
    redis.call('PEXPIRE', KEYS[1], window)
    return 1
end
return 0
