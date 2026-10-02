-- ============================================================================
-- 兑换码兑换优惠券 —— 兑换资格校验脚本（day11 3.2）
-- ============================================================================
-- 目的：和 receive_coupon.lua 一样，把多次 Redis 交互压成「一次 EVAL」。
--       兑换这一步原本要 5~6 次往返（判重 / 反查券 / 读缓存 / 校时间 / 校限领 / 标记），
--       外加 Redisson 的加锁解锁各一次 —— 现在一次搞定，而且不需要锁。
--
-- 参数约定：
--   KEYS[1] = coupon:code:map      兑换标记 BitMap（offset = 序列号）
--   KEYS[2] = coupon:code:range    券号段 ZSet（member = 券id，score = 该券号段的最大序列号）
--   ARGV[1] = serialNum            从兑换码解析出来的序列号
--   ARGV[2] = serialNum + 5000     ZSet 范围查询的上界（依据见第 2 步注释）
--   ARGV[3] = userId
--
-- 返回值（Java 侧用作 EXCHANGE_COUPON_ERROR_MSG 的下标）：
--   "1" ~ "5" = 失败，对应错误消息数组的下标 0~4
--   其他（一串 16 位以上的数字） = 成功，值是券id
--   ★ Java 侧靠 "结果 < 10" 来区分成功和失败 —— 前提是券id 远大于 10
--     （本项目券id 是雪花号/大自增号，19 位，不会撞上 1~5）
-- ============================================================================


-- 1.这个码兑换过没有？
--   ★ 注意是 GETBIT（只读），标记用的 SETBIT 放在最后一步（第 6 步）。
--   ★ 为什么可以"先判断、后标记"，而不像 Java 版那样"先占位、失败再释放"：
--     整个脚本在 Redis 里原子执行，"判断"和"标记"之间不可能被别的命令插队。
--     Java 版之所以必须先占位，就是因为多条命令之间有窗口。
--   ★ 用 SETBIT 的返回值也能一步完成判断+占位，但那样一旦后面校验失败或发 MQ 失败，
--     就得再写一次 SETBIT 0 去释放 —— 而且无法区分那个位是自己占的还是别人占的。
--     放在最后就完全没有这个问题：只有校验全过了才写。
if (redis.call('GETBIT', KEYS[1], ARGV[1]) == 1) then
    return "1"
end


-- 2.反查这个序列号属于哪张券（不查数据库）
--   ★ 原理：生成兑换码时每张券申请的是一整段连续号，所以一张券占用的号码
--     就是一个"连续、不重叠"的区间。于是只需要在 ZSet 里存每张券的"号段上界"，
--     找「score 不小于 serialNum 的第一个成员」就能定位到券。
--       券A 分到   1 ~ 6000    → {券A: 6000}
--       券B 分到 6001 ~ 12100  → {券B: 12100}
--       serialNum=8000 → 第一个 ≥8000 的是 12100 → 券B ✅
--   ★ 上界为什么是 serialNum + 5000：
--     单张券的号段跨度 = coupon.totalNum，而 CouponFormDTO 上有 @Range(max = 5000)，
--     即最长也只有 5000。覆盖 serialNum 的那张券，其上界 ≤ serialNum + 4999，
--     所以 [serialNum, serialNum + 5000] 一定能括住正确答案。
--   ★ LIMIT 0 1：按 score 升序取第 1 个。
local arr = redis.call('ZRANGEBYSCORE', KEYS[2], ARGV[1], ARGV[2], 'LIMIT', 0, 1)
if (#arr == 0) then
    return "2"
end
local cid = arr[1]


-- 3.拼出券缓存 / 限领计数两个 key，并检查券是否还在发放中
--   ★ 这里的前缀是"写死"在脚本里的。它和 Java 常量
--     PromotionConstants.COUPON_CACHE_KEY_PREFIX 重复了一份 —— 如果哪天前缀要改，
--     别忘了同时改这里。（更规范的做法是把前缀当 ARGV 传进来，代价是脚本可读性略降；
--     这里保留讲义的写法，方便和讲义对照。）
--   ★ 缓存不存在 → 券不在发放中（暂停/删除会把缓存删掉），和 receive 脚本同一个道理。
local _k1 = "prs:coupon:" .. cid
local _k2 = "prs:user:coupon:" .. cid
if (redis.call('EXISTS', _k1) == 0) then
    return "3"
end


-- 4.活动结束时间校验
--   ★★ 必须 *1000 ★★
--   redis.call('time')[1] 是「秒」（≈1.79e9），
--   而缓存里的 issueEndTime 是 DateUtils.toEpochMilli 存的「毫秒」（≈1.79e12）。
--   不比单位就比大小 → 秒永远小于毫秒 → 恒 false → 校验失效、过期券也能兑。
if (tonumber(redis.call('time')[1]) * 1000 > tonumber(redis.call('HGET', _k1, 'issueEndTime'))) then
    return "4"
end


-- 5.每人限领数量校验
--   ★ HINCRBY 是原子的（一条命令完成"读+加+写"），不会超领。
--   ★ field 用 ARGV[3]（具体 userId），不能写字面量。
if (tonumber(redis.call('HGET', _k1, 'userLimit')) < redis.call('HINCRBY', _k2, ARGV[3], 1)) then
    -- 超出限领要把刚加的还回去，否则用户的计数被白占
    redis.call('HINCRBY', _k2, ARGV[3], -1)
    return "5"
end


-- 6.全部通过：标记这个码已兑换，并返回券id
--   ★ SETBIT 放最后，是"校验全过了才写"的意思（见第 1 步的说明）。
--     它的返回值是「旧值」，正常情况下是 0（说明这一位之前没被占）。
redis.call('SETBIT', KEYS[1], ARGV[1], "1")
return cid
