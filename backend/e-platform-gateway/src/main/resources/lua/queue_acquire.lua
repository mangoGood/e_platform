-- 秒杀排队：申请名额（原子）
--
-- KEYS[1] = queue:permits:active  ZSet, member=票据, score=名额过期时间戳(ms)
-- KEYS[2] = queue:waiting         ZSet, member=票据, score=入队时间戳(ms)
--
-- ARGV[1] = now_ms          当前时间戳(ms)，由网关传入而非用 TIME 命令 ——
--                           TIME 是非确定性命令，会让脚本无法被复制到从库/AOF
-- ARGV[2] = claim_token     客户端 X-Queue-Token 头带来的票据，无则传空串
-- ARGV[3] = permits         并发名额上限
-- ARGV[4] = max_length      等待队列最大长度
-- ARGV[5] = permit_ttl_ms   名额有效期(ms)
-- ARGV[6] = wait_timeout_ms 排队等待超时(ms)
-- ARGV[7] = fresh_token     服务端新生成的票据，仅在需要「发新票」时使用
-- ARGV[8] = key_ttl_ms      两个 ZSet 键自身的存活时间(ms)
--
-- 返回 {code, rank}：
--   code =  1  已持有名额（客户端重发路径），放行，用 claim_token 归还
--   code =  2  当场发放新名额，放行，用 fresh_token 归还
--   code =  0  已入队，rank 为 0 起算的位次
--   code = -1  队列已满
-- 两个返回元素<b>都是整数</b>：Lua 表里混放字符串会让 Spring 的结果反序列化行为
-- 依赖 valueSerializer，跨版本不稳定；票据字符串 Java 侧本来就有，不必从 Redis 回传。

local activeKey = KEYS[1]
local waitingKey = KEYS[2]

local now = tonumber(ARGV[1])
local claimToken = ARGV[2]
local permits = tonumber(ARGV[3])
local maxLength = tonumber(ARGV[4])
local permitTtlMs = tonumber(ARGV[5])
local waitTimeoutMs = tonumber(ARGV[6])
local freshToken = ARGV[7]
local keyTtlMs = tonumber(ARGV[8])

-- 1. 回收过期名额。这是「客户端拿到名额后崩溃」的唯一兜底：
--    没有它，名额会永久泄漏，最终把并发数打到 0，整个下单入口静默死掉。
redis.call('ZREMRANGEBYSCORE', activeKey, '-inf', now)

-- 2. 清理等待超时者，避免僵尸票据长期占据队首、阻塞后面所有人。
redis.call('ZREMRANGEBYSCORE', waitingKey, '-inf', now - waitTimeoutMs)

-- 3. 客户端带票重发：票据仍在活跃集合中即放行，并顺带续期。
--    续期是必要的 —— 客户端从「轮询到 ready」到「真正重发请求」之间有网络往返，
--    不续期的话长尾请求会在转发途中把名额过期掉，release 时 ZREM 到一个空成员，
--    表现为名额提前被别人抢走。
if claimToken ~= '' then
    if redis.call('ZSCORE', activeKey, claimToken) then
        redis.call('ZADD', activeKey, now + permitTtlMs, claimToken)
        redis.call('PEXPIRE', activeKey, keyTtlMs)
        return {1, 0}
    end
end

local activeCount = redis.call('ZCARD', activeKey)
local waitingCount = redis.call('ZCARD', waitingKey)

-- 4. 快路径：有空位且<b>无人在排队</b>时当场发票，不必绕一圈轮询。
--    `waitingCount == 0` 这个条件不能省：否则新来的请求会插到排队者前面，
--    队尾的人可能永远等不到（饿死）。
if activeCount < permits and waitingCount == 0 then
    redis.call('ZADD', activeKey, now + permitTtlMs, freshToken)
    redis.call('PEXPIRE', activeKey, keyTtlMs)
    return {2, 0}
end

-- 5. 队列已满：直接拒绝，不入队。让客户端立刻失败，好过让它排一条注定超时的队。
if waitingCount >= maxLength then
    return {-1, 0}
end

-- 6. 入队。score 用入队时刻，ZRANK 天然给出 FIFO 位次。
redis.call('ZADD', waitingKey, now, freshToken)
redis.call('PEXPIRE', waitingKey, keyTtlMs)

local rank = redis.call('ZRANK', waitingKey, freshToken)
if not rank then
    -- 理论不可达（刚 ZADD 完）。返回 nil 会截断 Lua 数组导致 Java 侧越界，
    -- 因此退化为「排在已知队尾」，宁可位次不准也不能返回残缺结构。
    rank = waitingCount
end

return {0, rank}
