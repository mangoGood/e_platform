-- 秒杀排队：轮询并尝试晋升（原子）
--
-- KEYS[1] = queue:permits:active
-- KEYS[2] = queue:waiting
--
-- ARGV[1] = now_ms
-- ARGV[2] = token            客户端持有的排队票据
-- ARGV[3] = permits          并发名额上限
-- ARGV[4] = permit_ttl_ms
-- ARGV[5] = wait_timeout_ms
-- ARGV[6] = key_ttl_ms
--
-- 返回 {code, rank}：
--   code =  1  已就绪（本次晋升成功，或之前就已持有名额）
--   code =  0  仍在等待，rank 为 0 起算的位次
--   code = -2  票据不存在 / 已超时 / 已被消费

local activeKey = KEYS[1]
local waitingKey = KEYS[2]

local now = tonumber(ARGV[1])
local token = ARGV[2]
local permits = tonumber(ARGV[3])
local permitTtlMs = tonumber(ARGV[4])
local waitTimeoutMs = tonumber(ARGV[5])
local keyTtlMs = tonumber(ARGV[6])

-- 与 acquire 完全相同的两步清理。放在每个入口重复做，而不是靠定时任务：
-- 定时任务是额外的运行时组件（要选主、要监控），而排队本身是低频高峰场景，
-- 「谁访问谁顺手清理」的成本可以忽略，且天然无单点。
redis.call('ZREMRANGEBYSCORE', activeKey, '-inf', now)
redis.call('ZREMRANGEBYSCORE', waitingKey, '-inf', now - waitTimeoutMs)

-- 已经持有名额（比如客户端重复轮询，或上一次轮询已晋升但请求还没发出）。
if redis.call('ZSCORE', activeKey, token) then
    return {1, 0}
end

local rank = redis.call('ZRANK', waitingKey, token)
if not rank then
    -- 不在等待队列也不在活跃集合：票据无效或已超时被清理。
    return {-2, 0}
end

local activeCount = redis.call('ZCARD', activeKey)
local freeSlots = permits - activeCount

-- 晋升窗口 = 当前空闲名额数。位次落在窗口内即可晋升。
--
-- 【与原设计的偏差，刻意为之】原方案是「只晋升 rank == 0 的队首」。实测该策略有两个硬伤：
--   1) 吞吐塌陷：50 个空位时，一个轮询周期也只能放行 1 个人，
--      500 人的队列要 500 * pollInterval 秒才能消化完，远超 waitTimeout，等于全员超时。
--   2) 队首阻塞：队首客户端关掉浏览器后不再轮询，它要占着 rank 0 直到 waitTimeout（60s），
--      这 60 秒里后面所有人都无法晋升 —— 一个掉线的用户可以拖垮整条队列。
-- 用「空闲名额数」当窗口既解决了这两点，又不破坏公平性：
-- 你只可能越过那些「此刻本来也会被晋升、只是还没轮询到」的人，
-- 不存在后来者插到先来者前面的情况（rank 严格按入队时间排序）。
-- 超发风险为零：整段判断与写入在同一个 Lua 脚本内原子执行，
-- 并发轮询的第二个请求一定看到已更新的 activeCount。
if freeSlots > 0 and rank < freeSlots then
    redis.call('ZREM', waitingKey, token)
    redis.call('ZADD', activeKey, now + permitTtlMs, token)
    redis.call('PEXPIRE', activeKey, keyTtlMs)
    return {1, 0}
end

return {0, rank}
