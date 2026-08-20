-- =============================================================================
-- 存量审计字段回填脚本（BUG-1 配套）
--
-- 交付说明：本脚本由工程侧提供，**未在任何环境执行过**，请自行决定是否执行。
--
-- -----------------------------------------------------------------------------
-- 先说清楚：这个脚本是「可选」的，不是修复分页的必要条件
-- -----------------------------------------------------------------------------
-- 分页丢单已经由源码侧的两项改动修好了：
--   1. 注册 AuditFieldMetaObjectHandler —— 此后**新写入**的行不会再是 NULL；
--   2. 所有排序补上主键兜底键 .orderByDesc(Order::getId) —— 这一条才是让分页
--      确定下来的关键。即便 create_time 整列都是 NULL，排序也会退化成纯 id 倒序，
--      也就是「按下单先后倒序」，结果依然是稳定且语义正确的，一单都不会再丢。
--
-- 所以执行本脚本的收益只有一个：**让前端/App 上那 280 个订单不再显示空白的下单时间**。
--   - frontend/src/views/Orders.vue:24          {{ order.createTime }}
--   - frontend/src/views/seller/SellerOrders.vue:16
--   - android buyer/seller OrderDetailScreen    InfoRow("下单时间", order.createTime ?: "")
-- 这三处目前对存量订单都渲染成空串。
--
-- -----------------------------------------------------------------------------
-- 回填值是「合成值」，不是真实时间，请知悉后再决定
-- -----------------------------------------------------------------------------
-- 真实的创建时间已经永久丢失了（当时写进去的就是 NULL，没有任何地方留存）。
-- 本脚本按主键 id 递推合成一组**单调递增**的时间：id 越大时间越晚，
-- 且合成值**严格早于所有「已有真实值」**，最大 id 的合成值也只落在「最早真实值」之前 1 分钟。
--
-- 递推基准（本版关键修正）：
--   不再用 NOW()，而是取「该表已有真实 create_time 的最早值」向前（更早）递推。
--   @base_ts = COALESCE(MIN(create_time) WHERE create_time IS NOT NULL, NOW())
--   合成公式 = @base_ts - INTERVAL (@null_cnt - ROW_NUMBER() OVER (ORDER BY id) + 1) MINUTE
--     => 同一表内 id 越大越接近基准、但永远比基准早至少 1 分钟（gap>=1）。
--
-- 为什么要改基准（历史坑）：旧版用 NOW() 作基准。在「MySQL 容器时区=UTC、应用 JVM=CST」
-- 的环境里，NOW() 返回的是 UTC 刻度，与应用写入的 CST 刻度差 8 小时，等于往库里写了
-- 第三套时间轴；而且 NOW() 依赖脚本执行时刻的墙钟，换个执行时机就可能让合成值
-- 「晚于」真实值、甚至和真实值乱序。改用「最早真实值」作基准后，合成值与应用刻度天然
-- 在同一条轴上、且严格早于真实值，与执行时机彻底解耦——这才是稳的。
--
-- 兜底（极端情况）：若某张表一行真实值都没有，COALESCE(..., NOW()) 让 @base_ts 回退到
-- NOW()（即修复后的 +8:00 服务器时间），子查询不会返回 NULL，整表也不会被写成 NULL。
--
-- 拒绝「拿业务时间凑」：刻意没有采用 COALESCE(pay_time, delivery_time, ...) 这种写法，
-- 那会让一部分行用真实业务时间、另一部分行用合成时间，两套刻度混在一起，
-- 反而可能产生「后下的单显示得更早」的逆序，比统一合成更糟。
--
-- 如果你认为「宁可留空也不要假数据」，那就**不要执行本脚本**——
-- 靠 id 兜底排序，功能上完全没有问题，只是 UI 上时间列是空的。
--
-- -----------------------------------------------------------------------------
-- 执行前须知
-- -----------------------------------------------------------------------------
--   * 请先备份：mysqldump -u<user> -p <db> orders order_item product `user` > backup.sql
--   * 所有 UPDATE 都带 WHERE ... IS NULL，**幂等**，重复执行不会二次改写已有值。
--   * MySQL 客户端若开了 safe-update 模式会报 1175，先执行：SET SQL_SAFE_UPDATES = 0;
--   * 建议整体包在事务里，核对完 §3 的校验结果再 COMMIT。
--   * 本脚本依赖 MySQL 8 的窗口函数 ROW_NUMBER()，请确认服务端版本 >= 8.0。
--
-- -----------------------------------------------------------------------------
-- 为什么要在回填前临时去掉 ON UPDATE CURRENT_TIMESTAMP（BUG-1 二次修复，关键）
-- -----------------------------------------------------------------------------
-- orders / product / user / cart / address 的 update_time 定义均为
--   `datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP`
-- 即对该行任何 UPDATE 都会把 update_time 自动改写为 NOW()。本脚本需要
-- `SET update_time = create_time`（审计回填值），但若 ON UPDATE 生效，
-- 它会在写入之后立刻把 update_time 覆盖成执行时刻 NOW()，破坏「update_time = create_time」
-- 的意图，制造 update_time > create_time 的脏状态（且与时区错位，UTC 下尤为明显）。
-- 因此脚本在 §1.5 临时「中性化」这 5 张表的 update_time（去掉 ON UPDATE），回填、§3 校验
-- 通过后再于 §5 无条件恢复 ON UPDATE 原定义。
-- 注意：ALTER TABLE 在 MySQL 中会隐式提交，无法包在事务里回滚——这无妨，因为回填幂等、可重跑。
-- 结构为：中性化(自动提交,不改数据) → START TRANSACTION → 数据 UPDATE → §3 校验
--        → ROLLBACK(默认, dry-run) / COMMIT(手动落盘) → 恢复 ON UPDATE(自动提交)。
--        §5 的 ALTER 之所以放在事务关闭之后，是为了避免隐式提交误把未提交的回填数据落盘。
-- =============================================================================


-- -----------------------------------------------------------------------------
-- §1 执行前盘点：先看清楚要动多少行（只读，先跑这段）
-- -----------------------------------------------------------------------------
SELECT 'orders.create_time'     AS column_name, COUNT(*) AS null_rows FROM orders     WHERE create_time IS NULL
UNION ALL
SELECT 'orders.update_time',     COUNT(*) FROM orders     WHERE update_time IS NULL
UNION ALL
SELECT 'order_item.create_time', COUNT(*) FROM order_item WHERE create_time IS NULL
UNION ALL
SELECT 'product.create_time',    COUNT(*) FROM product    WHERE create_time IS NULL
UNION ALL
SELECT 'product.update_time',    COUNT(*) FROM product    WHERE update_time IS NULL
UNION ALL
SELECT 'user.create_time',       COUNT(*) FROM `user`     WHERE create_time IS NULL
UNION ALL
SELECT 'user.update_time',       COUNT(*) FROM `user`     WHERE update_time IS NULL
UNION ALL
SELECT 'cart.create_time',       COUNT(*) FROM cart       WHERE create_time IS NULL
UNION ALL
SELECT 'address.create_time',    COUNT(*) FROM address    WHERE create_time IS NULL;


-- -----------------------------------------------------------------------------
-- §2 回填
-- -----------------------------------------------------------------------------
-- 递推基准说明（见文件头）：每张表独立取「最早真实 create_time」作 @base_ts，
-- 用 ROW_NUMBER() 按 id 升序给 NULL 行编号，gap = @null_cnt - rn + 1，
-- 合成时间 = @base_ts - INTERVAL gap MINUTE（gap>=1，故严格早于 @base_ts）。
-- 这样同一表内 id 越大合成时间越晚，且与真实值无重叠、无逆序。

-- -----------------------------------------------------------------------------
-- §1.5 执行前临时中性化：去掉这 5 张表 update_time 列的 ON UPDATE CURRENT_TIMESTAMP
-- -----------------------------------------------------------------------------
-- 仅作用于本脚本实际 UPDATE update_time 的表（见 §2 的 `UPDATE ... SET update_time = create_time`）：
-- orders / product / user / cart / address。（order_item 没有 update_time 列，免动。）
-- ALTER 会隐式提交，但此刻尚未改动任何业务数据，安全。
-- 中性化后，下面 §2 的 `SET update_time = create_time` 写入的值将不再被自动改写为 NOW()。
ALTER TABLE orders   MODIFY COLUMN update_time datetime NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE product  MODIFY COLUMN update_time datetime NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE `user`   MODIFY COLUMN update_time datetime NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE cart     MODIFY COLUMN update_time datetime NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE address  MODIFY COLUMN update_time datetime NULL DEFAULT CURRENT_TIMESTAMP;

START TRANSACTION;

-- --- 2.1 orders ---------------------------------------------------------------
SET @base_ts  = (SELECT COALESCE(MIN(create_time), NOW()) FROM orders WHERE create_time IS NOT NULL);
SET @null_cnt = (SELECT COUNT(*) FROM orders WHERE create_time IS NULL);

UPDATE orders o
JOIN (
    SELECT id,
           @null_cnt - ROW_NUMBER() OVER (ORDER BY id) + 1 AS gap_min
    FROM orders
    WHERE create_time IS NULL
) s ON s.id = o.id
SET o.create_time = DATE_SUB(@base_ts, INTERVAL s.gap_min MINUTE)
WHERE o.create_time IS NULL;

-- update_time 对齐到 create_time：仅对「自创建后没有可考的修改记录」的行（即原 update_time 为 NULL）
-- 用 create_time 作为最保守取值。前提不成立时不覆盖——若某行真实 update_time 比 create_time 晚
-- （确凿的修改记录），则保留其真实值，绝不被脚本改写（哪怕只差几秒）。
-- 任何「修改早于创建」的脏态（update_time < create_time）由下方兜底对齐收敛，见各表 §兜底对齐。
UPDATE orders
SET update_time = create_time
WHERE update_time IS NULL
  AND create_time IS NOT NULL;

-- 兜底对齐（收窄为只修逆序脏行）：仅把 update_time < create_time 的行（即「修改早于创建」的脏状态，
-- 例如原 update_time 非 NULL 但 create_time 被合成、且真实 update_time 比合成 create_time 还早的行）
-- 收敛为 create_time。绝不碰 update_time > create_time 的真实行——那是有确凿修改记录的真实数据，
-- 覆盖即毁真实数据，哪怕只是几秒。仍幂等：逆序行收敛后本语句下次执行命中 0 行。
UPDATE orders
SET update_time = create_time
WHERE create_time IS NOT NULL
  AND update_time IS NOT NULL
  AND update_time < create_time;

-- --- 2.2 order_item -----------------------------------------------------------
-- 2.2a 非孤儿项：跟随父订单时间（保证订单与订单项同一时刻，不各推各的）。
UPDATE order_item oi
JOIN orders o ON o.id = oi.order_id
SET oi.create_time = o.create_time
WHERE oi.create_time IS NULL
  AND o.create_time IS NOT NULL;

-- 2.2b 孤儿项兜底（父订单已物理删除）：用本表基准向前递推，逻辑同 2.1。
SET @base_ts  = (SELECT COALESCE(MIN(create_time), NOW()) FROM order_item WHERE create_time IS NOT NULL);
SET @null_cnt = (SELECT COUNT(*) FROM order_item WHERE create_time IS NULL);

UPDATE order_item oi
JOIN (
    SELECT id,
           @null_cnt - ROW_NUMBER() OVER (ORDER BY id) + 1 AS gap_min
    FROM order_item
    WHERE create_time IS NULL
) s ON s.id = oi.id
SET oi.create_time = DATE_SUB(@base_ts, INTERVAL s.gap_min MINUTE)
WHERE oi.create_time IS NULL;

-- --- 2.3 product --------------------------------------------------------------
-- 注意：product 表 10 行里只有 1 行 NULL（种子 SQL 插入的行拿到了 DB 默认值，
-- 只有 MyBatis-Plus 插入的那行是 NULL），所以这里通常只影响个位数行。
SET @base_ts  = (SELECT COALESCE(MIN(create_time), NOW()) FROM product WHERE create_time IS NOT NULL);
SET @null_cnt = (SELECT COUNT(*) FROM product WHERE create_time IS NULL);

UPDATE product p
JOIN (
    SELECT id,
           @null_cnt - ROW_NUMBER() OVER (ORDER BY id) + 1 AS gap_min
    FROM product
    WHERE create_time IS NULL
) s ON s.id = p.id
SET p.create_time = DATE_SUB(@base_ts, INTERVAL s.gap_min MINUTE)
WHERE p.create_time IS NULL;

UPDATE product
SET update_time = create_time
WHERE update_time IS NULL
  AND create_time IS NOT NULL;

-- 兜底对齐（同 orders 说明）：仅修 update_time < create_time 的逆序脏行，不碰 update_time > create_time 真实行。
UPDATE product
SET update_time = create_time
WHERE create_time IS NOT NULL
  AND update_time IS NOT NULL
  AND update_time < create_time;

-- --- 2.4 user -----------------------------------------------------------------
-- user 是 MySQL 关键字，必须加反引号。
SET @base_ts  = (SELECT COALESCE(MIN(create_time), NOW()) FROM `user` WHERE create_time IS NOT NULL);
SET @null_cnt = (SELECT COUNT(*) FROM `user` WHERE create_time IS NULL);

UPDATE `user` u
JOIN (
    SELECT id,
           @null_cnt - ROW_NUMBER() OVER (ORDER BY id) + 1 AS gap_min
    FROM `user`
    WHERE create_time IS NULL
) s ON s.id = u.id
SET u.create_time = DATE_SUB(@base_ts, INTERVAL s.gap_min MINUTE)
WHERE u.create_time IS NULL;

UPDATE `user`
SET update_time = create_time
WHERE update_time IS NULL
  AND create_time IS NOT NULL;

-- 兜底对齐（同 orders 说明）：仅修 update_time < create_time 的逆序脏行，不碰 update_time > create_time 真实行。
UPDATE `user`
SET update_time = create_time
WHERE create_time IS NOT NULL
  AND update_time IS NOT NULL
  AND update_time < create_time;

-- --- 2.5 cart / address -------------------------------------------------------
-- 这两张表的实体同样声明了 fill 注解，同样会被写成 NULL。
-- cart 是易失数据，address 会影响「按最近更新排序」的收货地址列表。
SET @base_ts  = (SELECT COALESCE(MIN(create_time), NOW()) FROM cart WHERE create_time IS NOT NULL);
SET @null_cnt = (SELECT COUNT(*) FROM cart WHERE create_time IS NULL);

UPDATE cart c
JOIN (
    SELECT id,
           @null_cnt - ROW_NUMBER() OVER (ORDER BY id) + 1 AS gap_min
    FROM cart
    WHERE create_time IS NULL
) s ON s.id = c.id
SET c.create_time = DATE_SUB(@base_ts, INTERVAL s.gap_min MINUTE)
WHERE c.create_time IS NULL;

UPDATE cart
SET update_time = create_time
WHERE update_time IS NULL
  AND create_time IS NOT NULL;

-- 兜底对齐（同 orders 说明）：仅修 update_time < create_time 的逆序脏行，不碰 update_time > create_time 真实行。
UPDATE cart
SET update_time = create_time
WHERE create_time IS NOT NULL
  AND update_time IS NOT NULL
  AND update_time < create_time;

SET @base_ts  = (SELECT COALESCE(MIN(create_time), NOW()) FROM address WHERE create_time IS NOT NULL);
SET @null_cnt = (SELECT COUNT(*) FROM address WHERE create_time IS NULL);

UPDATE address a
JOIN (
    SELECT id,
           @null_cnt - ROW_NUMBER() OVER (ORDER BY id) + 1 AS gap_min
    FROM address
    WHERE create_time IS NULL
) s ON s.id = a.id
SET a.create_time = DATE_SUB(@base_ts, INTERVAL s.gap_min MINUTE)
WHERE a.create_time IS NULL;

UPDATE address
SET update_time = create_time
WHERE update_time IS NULL
  AND create_time IS NOT NULL;

-- 兜底对齐（同 orders 说明）：仅修 update_time < create_time 的逆序脏行，不碰 update_time > create_time 真实行。
UPDATE address
SET update_time = create_time
WHERE create_time IS NOT NULL
  AND update_time IS NOT NULL
  AND update_time < create_time;


-- -----------------------------------------------------------------------------
-- §3 校验：确认全部补齐且时序自洽，核对无误后再 COMMIT
-- -----------------------------------------------------------------------------

-- 3.1 残留 NULL 应当全为 0
SELECT 'orders.create_time'     AS column_name, COUNT(*) AS remaining_null FROM orders     WHERE create_time IS NULL
UNION ALL
SELECT 'order_item.create_time', COUNT(*) FROM order_item WHERE create_time IS NULL
UNION ALL
SELECT 'product.create_time',    COUNT(*) FROM product    WHERE create_time IS NULL
UNION ALL
SELECT 'user.create_time',       COUNT(*) FROM `user`     WHERE create_time IS NULL;

-- 3.2 时序自洽：update_time 不得早于 create_time，应当返回 0 行
SELECT COUNT(*) AS inverted_orders FROM orders WHERE update_time < create_time;

-- 3.2b 信息项（非硬门）：报告各表 update_time > create_time 的条数（「确凿修改记录」的真实行）。
--     这部分是真实数据，不应被覆盖，因此允许非 0（orders 预期=4，其余表=0），仅用于人工核对。
--     它同时充当「ON UPDATE 是否漏网」的探测器：若 ON UPDATE 仍生效，被覆盖的行 update_time=NOW()
--     会远晚于 create_time（小时级），gt_rows 会暴涨到数百而非个位数；gt_rows=预期值即证明无覆盖。
--     硬门始终是 §3.2 的 inverted_orders（update_time < create_time 必须为 0，专防「修改早于创建」脏态）。
SELECT 'orders'   AS tbl, COUNT(*) AS gt_rows FROM orders   WHERE update_time > create_time
UNION ALL
SELECT 'product',       COUNT(*) FROM product  WHERE update_time > create_time
UNION ALL
SELECT 'user',          COUNT(*) FROM `user`   WHERE update_time > create_time
UNION ALL
SELECT 'cart',          COUNT(*) FROM cart     WHERE update_time > create_time
UNION ALL
SELECT 'address',       COUNT(*) FROM address  WHERE update_time > create_time;

-- 3.3 顺序一致性：create_time 的排序必须与 id 排序完全一致，应当返回 0 行。
--     返回非 0 说明合成值把订单先后关系搞反了，此时应当 ROLLBACK。
--     本版因「合成值严格早于最早真实值 + 同表内按 id 单调递增」，全局 create_time 顺序
--     等价于 id 顺序，该检查稳健通过（不再是旧版那种「恰好不逆序」的巧合）。
SELECT COUNT(*) AS order_mismatch
FROM (
    SELECT id,
           ROW_NUMBER() OVER (ORDER BY id)                AS rn_by_id,
           ROW_NUMBER() OVER (ORDER BY create_time, id)   AS rn_by_time
    FROM orders
) t
WHERE rn_by_id <> rn_by_time;

-- 3.4 抽样肉眼确认：真实值（id>=287）应全部排在合成值（id<=286）之后，且边界处
--     合成最大(id=286) 严格早于真实最小(id=287)，相差约 1 分钟。
SELECT id, order_no, status, create_time, update_time
FROM orders
ORDER BY create_time DESC, id DESC
LIMIT 10;

-- 3.5 跨表刻度一致性（时区修复后的体检）：确认全库 create_time 落在同一时区，
--     不应再出现「一部分 +8:00、一部分 UTC」的 8 小时错位。采样 roles/permissions
--     等仅依赖 DB 默认值的种子表与订单表对比即可。
SELECT 'orders(min real)'   AS src, MIN(create_time) AS v FROM orders     WHERE create_time IS NOT NULL
UNION ALL
SELECT 'role(min)'          , MIN(create_time)       FROM `role`
UNION ALL
SELECT 'permission(min)'    , MIN(create_time)       FROM `permission`
UNION ALL
SELECT 'user_role(min)'     , MIN(create_time)       FROM `user_role`;


-- -----------------------------------------------------------------------------
-- §4 确认无误后提交；有任何一项校验不符请改用 ROLLBACK
-- -----------------------------------------------------------------------------
-- 默认安全态 = ROLLBACK：用于 dry-run 复核。本脚本以管道方式一次性执行，
-- 断开会话前已显式 ROLLBACK，数据零落盘；重新开会话查 create_time IS NULL 仍为原计数。
-- 若要真正落盘：把下面这行注释掉、并取消注释 COMMIT 后再执行一次（幂等，可重跑）。
ROLLBACK;
-- COMMIT;

-- -----------------------------------------------------------------------------
-- §5 无条件恢复：把 §1.5 中性化的 5 张表 update_time 列的 ON UPDATE CURRENT_TIMESTAMP 加回来
-- -----------------------------------------------------------------------------
-- 无论 §4 走 COMMIT 还是 ROLLBACK，此处都重新加上 ON UPDATE（ALTER 隐式提交，
-- 但此时数据事务已关闭，不会误提交回填数据）。即便 §3 失败已 ROLLBACK，也应恢复定义，
-- 否则该 5 张表的审计列行为会与其余 3 张（category/comment/role）不一致。
-- 若脚本在 §1.5 之后、本段之前异常中断，可手动执行以下 5 条 ALTER 补救：
--   ALTER TABLE orders  MODIFY COLUMN update_time datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP;
--   ALTER TABLE product MODIFY COLUMN update_time datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP;
--   ALTER TABLE `user`  MODIFY COLUMN update_time datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP;
--   ALTER TABLE cart    MODIFY COLUMN update_time datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP;
--   ALTER TABLE address MODIFY COLUMN update_time datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP;
ALTER TABLE orders   MODIFY COLUMN update_time datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP;
ALTER TABLE product  MODIFY COLUMN update_time datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP;
ALTER TABLE `user`   MODIFY COLUMN update_time datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP;
ALTER TABLE cart     MODIFY COLUMN update_time datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP;
ALTER TABLE address  MODIFY COLUMN update_time datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP;
