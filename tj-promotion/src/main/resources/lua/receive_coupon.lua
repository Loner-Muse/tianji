-- ============================================================================
-- 手动领取优惠券 —— 领取资格校验脚本（day11 3.2）
-- ============================================================================
-- 目的：把原先 4~5 次 Redis 交互（读缓存 / 校时间 / 校限领 / 扣库存）
--       压成「一次 EVAL」，消除多次网络往返；同时因为脚本在 Redis 里原子执行，
--       "判断"和"写入"之间没有窗口，所以**不需要再加分布式锁**。
--
-- 参数约定：
--   KEYS[1] = prs:coupon:{couponId}        券缓存 Hash
--             （字段：issueBeginTime / issueEndTime / totalNum / userLimit）
--   KEYS[2] = prs:user:coupon:{couponId}   限领计数 Hash（field = userId，value = 已领数）
--   ARGV[1] = userId
--
-- 返回值（Java 侧用作 RECEIVE_COUPON_ERROR_MSG 的下标，0 表示成功）：
--   0 = 校验通过（库存已扣、限领计数已加）
--   1 = 券不存在 / 未在发放中
--   2 = 库存不足
--   3 = 活动已结束
--   4 = 超出每人限领数量
-- ============================================================================


-- 1.券缓存不存在 → 这张券没在发放中
--   ★ 为什么判 "缓存存在" 就够了、不用再校验 status：
--     我们的设计是「暂停 / 删除券时会把缓存删掉」，
--     所以 "缓存存在" 就等价于 "正在发放中"，
--     没必要再在缓存里存一个 status 字段。
--   ★ 为什么也不用校验 issueBeginTime（活动是否已开始）：
--     延时发放的券是在定时任务 beginIssueBatch 到点时才写缓存的，
--     也就是说「还没开始的券压根不在缓存里」，这一步的 exists 已经拦住了。
if (redis.call('exists', KEYS[1]) == 0) then
    return 1
end


-- 2.库存校验：先读出来判断，最后一步才真正扣
--   ★ 在 Lua 里「先读后扣」是安全的 —— 脚本原子执行，
--     读到的值到扣的时候一定还有效（中间不会有别的命令插进来）。
--   ★ 而且这么写还有个好处：库存不足时压根没扣，自然就不用写回滚逻辑。
--   ★ 对比 Java 版：要么"先读判断"但非原子，要么"先扣再判返回值"然后必须成对回滚。
--     Lua 一次性绕过了这个两难。
if (tonumber(redis.call('hget', KEYS[1], 'totalNum')) <= 0) then
    return 2
end


-- 3.活动结束时间校验
--   ★★ 必须 *1000，这是修复后的关键一行 ★★
--   左边 redis.call('time') 返回的是 [秒, 微秒]，[1] 是「秒」（10 位，≈1.79e9）
--   右边缓存里的 issueEndTime 是 cacheCouponInfo 用 DateUtils.toEpochMilli
--   存进去的「毫秒」时间戳（13 位，≈1.79e12）
--   如果直接比：秒 > 毫秒 恒为 false → 这个校验永远不触发，已过期的券也能领。
--   所以要把左边补齐成毫秒。
if (tonumber(redis.call('time')[1]) * 1000 > tonumber(redis.call('hget', KEYS[1], 'issueEndTime'))) then
    return 3
end


-- 4.每人限领数量校验
--   ★ HINCRBY 是原子命令：一条命令内部完成「读 + 加 + 写」，
--     不存在"两个请求都读到旧值、都判断通过"的并发窗口。
--     如果用 HGET + 判断 + HINCRBY 三条命令，就会超领。
--   ★ field 用 ARGV[1]（具体的 userId），绝不能写成字面量，
--     否则全站用户共用一个计数器。
if (tonumber(redis.call('hget', KEYS[1], 'userLimit')) < redis.call('hincrby', KEYS[2], ARGV[1], 1)) then
    -- 超出限领要把刚加上的这次计数还回去：
    -- 否则用户的计数被白占，下次就算有额度也永远超限。
    redis.call('hincrby', KEYS[2], ARGV[1], -1)
    return 4
end


-- 5.全部校验通过 → 扣减库存
--   写成 '-1'（字符串）也能工作，Redis 会自己解析成数字
redis.call('hincrby', KEYS[1], 'totalNum', -1)
return 0
