-- 2026-11-10 给 tb_order 增加 (user_id, product_id) 唯一索引，数据库层兜底防重。
-- 注意：该索引已被 2026-11-18_order_active_unique.sql 的 active_flag 唯一索引替代。
-- 新库或已应用新迁移的库不要执行本脚本；若误执行，下面的判断会跳过，避免把旧索引加回来。
-- 执行前先确认没有重复数据：
-- SELECT user_id, product_id, COUNT(*) AS c FROM tb_order GROUP BY user_id, product_id HAVING c > 1;
SET @new_index_exists = (
    SELECT COUNT(*)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'tb_order'
      AND INDEX_NAME = 'uk_order_user_product_active'
);
SET @index_exists = (
    SELECT COUNT(*)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'tb_order'
      AND INDEX_NAME = 'uk_order_user_product'
);
SET @ddl = IF(@new_index_exists > 0,
    'SELECT ''superseded by uk_order_user_product_active'' AS msg',
    IF(@index_exists = 0,
        'ALTER TABLE tb_order ADD UNIQUE INDEX uk_order_user_product (user_id, product_id)',
        'SELECT ''uk_order_user_product already exists'' AS msg'));
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 验证
SHOW INDEX FROM tb_order WHERE Key_name = 'uk_order_user_product';
