-- =============================================================================
-- e_platform 增量迁移脚本 v2
-- 内容：RBAC 四表 + comment 表三级评论扩展 + product 评分聚合字段 + 存量数据回填
-- 特性：**可重复执行**（幂等）。任何一步已存在则跳过，不报错。
-- 用法：mysql -h127.0.0.1 -uroot -proot < database/migration-v2.sql
--      或 docker exec -i e-platform-mysql mysql -uroot -proot < database/migration-v2.sql
-- =============================================================================

SET NAMES utf8mb4;
CREATE DATABASE IF NOT EXISTS `e_platform` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `e_platform`;

-- -----------------------------------------------------------------------------
-- 0. 幂等辅助存储过程（MySQL 8 不支持 ADD COLUMN IF NOT EXISTS）
-- -----------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS `sp_add_column_if_missing`;
DROP PROCEDURE IF EXISTS `sp_add_index_if_missing`;
DROP PROCEDURE IF EXISTS `sp_drop_index_if_exists`;

DELIMITER $$

CREATE PROCEDURE `sp_add_column_if_missing`(
    IN p_table  VARCHAR(64),
    IN p_column VARCHAR(64),
    IN p_ddl    VARCHAR(1000)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND COLUMN_NAME = p_column
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN ', p_ddl);
        PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
    END IF;
END$$

CREATE PROCEDURE `sp_add_index_if_missing`(
    IN p_table VARCHAR(64),
    IN p_index VARCHAR(64),
    IN p_ddl   VARCHAR(1000)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND INDEX_NAME = p_index
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD ', p_ddl);
        PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
    END IF;
END$$

CREATE PROCEDURE `sp_drop_index_if_exists`(
    IN p_table VARCHAR(64),
    IN p_index VARCHAR(64)
)
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND INDEX_NAME = p_index
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` DROP INDEX `', p_index, '`');
        PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
    END IF;
END$$

DELIMITER ;

-- =============================================================================
-- 1. RBAC 四表
-- =============================================================================

CREATE TABLE IF NOT EXISTS `role` (
  `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '角色ID',
  `code`        VARCHAR(50) NOT NULL COMMENT '角色编码，如 ROLE_BUYER',
  `name`        VARCHAR(50) NOT NULL COMMENT '角色名称',
  `description` VARCHAR(255) DEFAULT NULL COMMENT '角色描述',
  `status`      TINYINT     DEFAULT 1 COMMENT '状态：0-禁用，1-正常',
  `create_time` DATETIME    DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`     TINYINT     DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色表';

CREATE TABLE IF NOT EXISTS `permission` (
  `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '权限ID',
  `code`        VARCHAR(64) NOT NULL COMMENT '权限码，格式 资源:动作，如 product:write',
  `name`        VARCHAR(64) NOT NULL COMMENT '权限名称',
  `resource`    VARCHAR(32) NOT NULL COMMENT '资源域，如 product / order / comment',
  `action`      VARCHAR(32) NOT NULL COMMENT '动作，如 read / write / create',
  `description` VARCHAR(255) DEFAULT NULL,
  `create_time` DATETIME    DEFAULT CURRENT_TIMESTAMP,
  `deleted`     TINYINT     DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_perm_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='权限表';

CREATE TABLE IF NOT EXISTS `role_permission` (
  `id`            BIGINT   NOT NULL AUTO_INCREMENT,
  `role_id`       BIGINT   NOT NULL COMMENT '角色ID',
  `permission_id` BIGINT   NOT NULL COMMENT '权限ID',
  `create_time`   DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_perm` (`role_id`, `permission_id`),
  KEY `idx_rp_role_id` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色-权限关联表';

CREATE TABLE IF NOT EXISTS `user_role` (
  `id`          BIGINT   NOT NULL AUTO_INCREMENT,
  `user_id`     BIGINT   NOT NULL COMMENT '用户ID',
  `role_id`     BIGINT   NOT NULL COMMENT '角色ID',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_role` (`user_id`, `role_id`),
  KEY `idx_ur_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户-角色关联表';

-- 1.1 内置角色（幂等）
INSERT INTO `role` (`code`, `name`, `description`) VALUES
  ('ROLE_BUYER',  '买家',   '普通买家，可浏览、下单、评价、追问'),
  ('ROLE_SELLER', '卖家',   '商家，可管理自己的商品与订单、回复评价；同时具备买家能力'),
  ('ROLE_ADMIN',  '管理员', '平台管理员，可管理用户、商品、评论')
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`), `description` = VALUES(`description`);

-- 1.2 内置权限（权限码格式：资源:动作）
INSERT INTO `permission` (`code`, `name`, `resource`, `action`, `description`) VALUES
  ('user:read',      '查看用户信息', 'user',    'read',   '查看自己的用户信息'),
  ('product:read',   '浏览商品',     'product', 'read',   '公开，保留权限码便于扩展'),
  ('product:write',  '维护商品',     'product', 'write',  '新增/修改商品（需归属校验）'),
  ('product:delete', '删除商品',     'product', 'delete', '删除商品（需归属校验）'),
  ('cart:manage',    '管理购物车',   'cart',    'manage', '增删改查购物车'),
  ('order:create',   '创建订单',     'order',   'create', '下单'),
  ('order:read',     '查看订单',     'order',   'read',   '查看自己的订单（需归属校验）'),
  ('order:manage',   '订单流转',     'order',   'manage', '卖家发货等状态流转（需归属校验）'),
  ('comment:create', '发表评价',     'comment', 'create', 'L1 购后评价（需购买校验）'),
  ('comment:reply',  '回复评价',     'comment', 'reply',  'L2 卖家回复（需商品归属校验）'),
  ('comment:ask',    '追问留言',     'comment', 'ask',    'L3 第三方追问'),
  ('comment:manage', '管理评论',     'comment', 'manage', '管理员隐藏/删除任意评论'),
  ('admin:access',   '后台访问',     'admin',   'access', '访问 /api/admin/**')
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`), `description` = VALUES(`description`);

-- 1.3 角色-权限绑定（幂等）
INSERT IGNORE INTO `role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id FROM `role` r JOIN `permission` p
WHERE r.code = 'ROLE_BUYER'
  AND p.code IN ('user:read','product:read','cart:manage','order:create','order:read',
                 'comment:create','comment:ask');

INSERT IGNORE INTO `role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id FROM `role` r JOIN `permission` p
WHERE r.code = 'ROLE_SELLER'
  AND p.code IN ('user:read','product:read','product:write','product:delete','cart:manage',
                 'order:create','order:read','order:manage',
                 'comment:create','comment:reply','comment:ask');

INSERT IGNORE INTO `role_permission` (`role_id`, `permission_id`)
SELECT r.id, p.id FROM `role` r JOIN `permission` p
WHERE r.code = 'ROLE_ADMIN';

-- 1.4 存量用户角色迁移：user_type 1->ROLE_BUYER, 2->ROLE_SELLER
INSERT IGNORE INTO `user_role` (`user_id`, `role_id`)
SELECT u.id, r.id FROM `user` u JOIN `role` r ON r.code = 'ROLE_BUYER'
WHERE u.user_type = 1 AND u.deleted = 0;

INSERT IGNORE INTO `user_role` (`user_id`, `role_id`)
SELECT u.id, r.id FROM `user` u JOIN `role` r ON r.code = 'ROLE_SELLER'
WHERE u.user_type = 2 AND u.deleted = 0;

-- =============================================================================
-- 2. comment 表：三级评论扩展
-- =============================================================================

CALL sp_add_column_if_missing('comment', 'parent_id',
  '`parent_id` BIGINT NOT NULL DEFAULT 0 COMMENT ''父评论ID：L1=0，L2/L3=所属L1的id'' AFTER `order_id`');

CALL sp_add_column_if_missing('comment', 'root_id',
  '`root_id` BIGINT NOT NULL DEFAULT 0 COMMENT ''根评论ID：L1=0（或自身id），L2/L3=所属L1的id'' AFTER `parent_id`');

CALL sp_add_column_if_missing('comment', 'type',
  '`type` TINYINT NOT NULL DEFAULT 1 COMMENT ''类型：1-买家评价(L1) 2-卖家回复(L2) 3-追问留言(L3)'' AFTER `root_id`');

CALL sp_add_column_if_missing('comment', 'reply_to_user_id',
  '`reply_to_user_id` BIGINT DEFAULT NULL COMMENT ''被回复用户ID，仅用于L3渲染 @昵称'' AFTER `type`');

CALL sp_add_column_if_missing('comment', 'seller_id',
  '`seller_id` BIGINT NOT NULL DEFAULT 0 COMMENT ''商品所属卖家ID（冗余，便于鉴权与卖家维度查询）'' AFTER `reply_to_user_id`');

CALL sp_add_column_if_missing('comment', 'reply_count',
  '`reply_count` INT NOT NULL DEFAULT 0 COMMENT ''子级(L2+L3)数量，仅L1维护'' AFTER `seller_id`');

CALL sp_add_column_if_missing('comment', 'order_item_id',
  '`order_item_id` BIGINT DEFAULT NULL COMMENT ''订单明细ID，L1唯一性判定辅助'' AFTER `order_id`');

-- rating 允许为空（L2/L3 无评分）
ALTER TABLE `comment` MODIFY COLUMN `rating` TINYINT DEFAULT NULL COMMENT '评分1-5星，仅L1有值';

-- 2.1 存量数据回填
UPDATE `comment` SET `type` = 1        WHERE `type` IS NULL OR `type` = 0;
UPDATE `comment` SET `parent_id` = 0   WHERE `parent_id` IS NULL;
UPDATE `comment` SET `root_id` = 0     WHERE `root_id` IS NULL;
UPDATE `comment` SET `reply_count` = 0 WHERE `reply_count` IS NULL;
UPDATE `comment` SET `rating` = 5      WHERE `type` = 1 AND (`rating` IS NULL OR `rating` < 1);

-- 回填 seller_id（从 product 表关联）
UPDATE `comment` c
  JOIN `product` p ON p.id = c.product_id
SET c.seller_id = p.seller_id
WHERE c.seller_id = 0 OR c.seller_id IS NULL;

-- 2.2 L1 唯一约束前置去重：同一 (order_id, product_id, user_id) 只保留 id 最小的一条
--     order_id 为 NULL 的记录（历史脏数据/L2/L3）不受唯一索引约束（MySQL 唯一索引允许多个 NULL）
DELETE c FROM `comment` c
JOIN (
    SELECT MIN(id) AS keep_id, order_id, product_id, user_id
    FROM `comment`
    WHERE type = 1 AND order_id IS NOT NULL AND deleted = 0
    GROUP BY order_id, product_id, user_id
    HAVING COUNT(*) > 1
) d ON c.order_id = d.order_id AND c.product_id = d.product_id
   AND c.user_id = d.user_id AND c.id <> d.keep_id
WHERE c.type = 1 AND c.deleted = 0;

-- 2.3 索引
CALL sp_add_index_if_missing('comment', 'idx_root_id',
  'KEY `idx_root_id` (`root_id`)');
CALL sp_add_index_if_missing('comment', 'idx_parent_id',
  'KEY `idx_parent_id` (`parent_id`)');
CALL sp_add_index_if_missing('comment', 'idx_product_type',
  'KEY `idx_product_type` (`product_id`, `type`, `status`, `deleted`)');
CALL sp_add_index_if_missing('comment', 'idx_seller_type',
  'KEY `idx_seller_type` (`seller_id`, `type`)');
CALL sp_add_index_if_missing('comment', 'uk_order_product_user',
  'UNIQUE KEY `uk_order_product_user` (`order_id`, `product_id`, `user_id`)');

-- =============================================================================
-- 3. product 表：评分聚合字段（同步更新 + 定时兜底，不引入 MQ）
-- =============================================================================

CALL sp_add_column_if_missing('product', 'rating_avg',
  '`rating_avg` DECIMAL(3,2) NOT NULL DEFAULT 0.00 COMMENT ''平均评分（L1聚合）'' AFTER `sales`');
CALL sp_add_column_if_missing('product', 'rating_count',
  '`rating_count` INT NOT NULL DEFAULT 0 COMMENT ''有效评价数（L1）'' AFTER `rating_avg`');

-- 初始回填
UPDATE `product` p
LEFT JOIN (
    SELECT product_id, ROUND(AVG(rating), 2) AS avg_r, COUNT(*) AS cnt
    FROM `comment`
    WHERE type = 1 AND status = 1 AND deleted = 0 AND rating IS NOT NULL
    GROUP BY product_id
) c ON c.product_id = p.id
SET p.rating_avg = IFNULL(c.avg_r, 0.00), p.rating_count = IFNULL(c.cnt, 0);

-- =============================================================================
-- 4. 演示数据补充（幂等）：一个管理员账号，密码同现有测试账号 123456
-- =============================================================================
INSERT INTO `user` (`username`, `password`, `email`, `phone`, `user_type`, `status`)
SELECT 'admin', '$2a$10$UaVk/LV666VTV13AWcP11epabX4G7E2PYCyTeCLsuu3Oyuj60J23K',
       'admin@example.com', '13800138000', 1, 1
WHERE NOT EXISTS (SELECT 1 FROM `user` WHERE username = 'admin');

INSERT IGNORE INTO `user_role` (`user_id`, `role_id`)
SELECT u.id, r.id FROM `user` u JOIN `role` r ON r.code = 'ROLE_ADMIN'
WHERE u.username = 'admin';

-- =============================================================================
-- 5. 清理辅助存储过程
-- =============================================================================
DROP PROCEDURE IF EXISTS `sp_add_column_if_missing`;
DROP PROCEDURE IF EXISTS `sp_add_index_if_missing`;
DROP PROCEDURE IF EXISTS `sp_drop_index_if_exists`;

SELECT '=== migration-v2.sql 执行完成 ===' AS result;
