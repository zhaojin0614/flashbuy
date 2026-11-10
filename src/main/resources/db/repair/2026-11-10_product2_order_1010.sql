-- 2026-11-10 对账修复：商品 2 的 Redis 资格 3 人/库存 497，DB 订单 2 条/库存 498。
-- 根因：stream.orders 中一条旧消息字段为 voucherId 而非 productId，消费时 productId 为空，
--       订单未落库，但 Redis 已预扣库存并写入资格。
-- 业务口径：为 1010 补订单，并扣减 DB 库存 1。
-- 脚本可重复执行：订单已存在时 INSERT 影响 0 行，后续 UPDATE 不会再次扣库存。
START TRANSACTION;

INSERT INTO tb_order (id, user_id, product_id, pay_type, status, create_time)
SELECT 656213368662654977, 1010, 2, 1, 1, '2026-11-04 08:43:01'
WHERE NOT EXISTS (
    SELECT 1 FROM tb_order WHERE id = 656213368662654977
)
AND NOT EXISTS (
    SELECT 1 FROM tb_order WHERE user_id = 1010 AND product_id = 2
);

SET @inserted = ROW_COUNT();

UPDATE tb_product_release
SET stock = stock - 1
WHERE product_id = 2
  AND stock > 0
  AND @inserted = 1;

COMMIT;

-- 验证
SELECT COUNT(*) AS orders FROM tb_order WHERE product_id = 2;
SELECT stock FROM tb_product_release WHERE product_id = 2;
