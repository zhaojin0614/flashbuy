-- 2026-11-18 取消后允许复购：把“所有订单唯一”改为“有效订单唯一”
--
-- 有效状态：1 待支付、2 已支付、3 已核销、5 退款中
-- 已结束状态：4 已取消、6 已退款（active_flag = NULL，可保留多条历史）
--
-- 执行前先确认没有重复的有效订单（正常应为空）：
-- SELECT user_id, product_id, COUNT(*) AS c
-- FROM tb_order WHERE status IN (1,2,3,5)
-- GROUP BY user_id, product_id HAVING c > 1;
--
-- 本脚本可重复执行。

-- 1. 删除旧的“所有订单唯一”索引
SET @old_index_exists = (
    SELECT COUNT(*)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'tb_order'
      AND INDEX_NAME = 'uk_order_user_product'
);
SET @ddl = IF(@old_index_exists > 0,
    'ALTER TABLE tb_order DROP INDEX uk_order_user_product',
    'SELECT ''uk_order_user_product already dropped'' AS msg');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. 增加可空生成列：有效订单为 1，已取消/已退款为 NULL
SET @column_exists = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'tb_order'
      AND COLUMN_NAME = 'active_flag'
);
SET @ddl = IF(@column_exists = 0,
    'ALTER TABLE tb_order ADD COLUMN active_flag TINYINT GENERATED ALWAYS AS (CASE WHEN status IN (1,2,3,5) THEN 1 ELSE NULL END) STORED COMMENT ''有效订单标记：1占用复购名额，NULL已结束'' AFTER status',
    'SELECT ''active_flag already exists'' AS msg');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3. 增加“有效订单唯一”索引：多个 NULL 不冲突，active_flag=1 最多一条
SET @new_index_exists = (
    SELECT COUNT(*)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'tb_order'
      AND INDEX_NAME = 'uk_order_user_product_active'
);
SET @ddl = IF(@new_index_exists = 0,
    'ALTER TABLE tb_order ADD UNIQUE INDEX uk_order_user_product_active (user_id, product_id, active_flag)',
    'SELECT ''uk_order_user_product_active already exists'' AS msg');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 验证
SHOW INDEX FROM tb_order WHERE Key_name IN ('uk_order_user_product', 'uk_order_user_product_active');