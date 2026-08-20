-- =============================================================================
-- e_platform 增量迁移脚本 v3
-- 内容：comment 表用户名快照字段（username / reply_to_username）
-- 背景：user 服务未提供 /user/internal/batch 批量查名接口，且 user 模块本轮不允许改动。
--       为避免评论树查询产生 N+1 次跨服务 RPC，改为在写入时落一份「昵称快照」。
--       快照缺失（存量数据）时由 product 侧 UserNameResolver 回源 /user/info/{id} 兜底。
-- 特性：**可重复执行**（幂等）。任何一步已存在则跳过，不报错。
-- 用法：mysql -h127.0.0.1 -uroot -proot < database/migration-v3.sql
--      或 docker exec -i e-platform-mysql mysql -uroot -proot < database/migration-v3.sql
-- =============================================================================

SET NAMES utf8mb4;
CREATE DATABASE IF NOT EXISTS `e_platform` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `e_platform`;

-- -----------------------------------------------------------------------------
-- 0. 幂等辅助存储过程（与 migration-v2.sql 同款写法）
-- -----------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS `sp_v3_add_column_if_missing`;

DELIMITER $$

CREATE PROCEDURE `sp_v3_add_column_if_missing`(
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

DELIMITER ;

-- =============================================================================
-- 1. comment 表：昵称快照
-- =============================================================================

CALL sp_v3_add_column_if_missing('comment', 'username',
  '`username` VARCHAR(50) DEFAULT NULL COMMENT ''发表者用户名快照，避免评论树查询 N+1 跨服务调用'' AFTER `user_id`');

CALL sp_v3_add_column_if_missing('comment', 'reply_to_username',
  '`reply_to_username` VARCHAR(50) DEFAULT NULL COMMENT ''被回复者用户名快照，仅 L3 使用'' AFTER `reply_to_user_id`');

-- 1.1 存量数据回填：从 user 表直接关联（同库，无需跨服务）
UPDATE `comment` c
  JOIN `user` u ON u.id = c.user_id
SET c.username = u.username
WHERE c.username IS NULL OR c.username = '';

UPDATE `comment` c
  JOIN `user` u ON u.id = c.reply_to_user_id
SET c.reply_to_username = u.username
WHERE c.reply_to_user_id IS NOT NULL
  AND (c.reply_to_username IS NULL OR c.reply_to_username = '');

-- =============================================================================
-- 2. 一致性兜底：root_id 自指回填
--    L1 在 v2 中默认 root_id=0，新代码统一约定 L1.root_id = 自身 id。
--    这里把存量 L1 的 root_id 一次性对齐，避免新旧数据两套语义。
-- =============================================================================
UPDATE `comment` SET `root_id` = `id` WHERE `type` = 1 AND (`root_id` = 0 OR `root_id` IS NULL);

-- 2.1 reply_count 重算（仅 L1 维护，统计其下 L2 + L3 的有效条数）
UPDATE `comment` l1
LEFT JOIN (
    SELECT root_id, COUNT(*) AS cnt
    FROM `comment`
    WHERE type IN (2, 3) AND deleted = 0 AND status = 1
    GROUP BY root_id
) s ON s.root_id = l1.id
SET l1.reply_count = IFNULL(s.cnt, 0)
WHERE l1.type = 1;

-- =============================================================================
-- 3. 清理辅助存储过程
-- =============================================================================
DROP PROCEDURE IF EXISTS `sp_v3_add_column_if_missing`;

SELECT '=== migration-v3.sql 执行完成 ===' AS result;
