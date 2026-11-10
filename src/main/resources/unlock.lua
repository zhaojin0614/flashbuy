-- 释放分布式锁：只有锁的持有者（标识一致）才能删除，保证“比较 + 删除”原子执行
--
-- KEYS[1] 锁 key，例如 lock:order:123
-- ARGV[1] 当前线程标识（ID_PREFIX + threadId）
--
-- 返回：1 = 删除成功；0 = 锁不存在或不是当前线程持有
--
-- 比较线程标示与锁中的标示是否一致
if(redis.call('get', KEYS[1]) ==  ARGV[1]) then
    -- 释放锁 del key
    return redis.call('del', KEYS[1])
end
return 0
