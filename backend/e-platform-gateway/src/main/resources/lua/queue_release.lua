-- 秒杀排队：归还名额（幂等）
--
-- KEYS[1] = queue:permits:active
-- ARGV[1] = token
--
-- 返回实际移除的成员数：1 = 本次真的归还了，0 = 票据已因 TTL 过期或已归还过。
--
-- 只有一条命令，为什么还要走脚本？
--   1) 与 acquire / poll 统一在 resources/lua/ 下管理，改并发语义时不会漏掉这一处；
--   2) 未来若要加「归还时立即唤醒队首」（ZPOPMIN waiting + ZADD active），
--      必须与 ZREM 原子执行，届时不用改调用方签名。
-- ZREM 天然幂等：重复归还只会返回 0，绝不会让 ZCARD 变负或让名额虚增。

return redis.call('ZREM', KEYS[1], ARGV[1])
