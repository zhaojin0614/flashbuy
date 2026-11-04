package com.flashbuy.service;

import com.flashbuy.dto.Result;
import com.flashbuy.entity.Order;
import com.baomidou.mybatisplus.spring.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IOrderService extends IService<Order> {

    Result seckillProduct(Long productId);

    /**
     * 落库创建订单（@Transactional 生效需要走代理调用）
     */
    void createOrder(Order order);

    /**
     * 查询当前用户对某商品的抢购状态：
     * qualified = 已获得抢购资格（Lua 阶段同步写入 Redis Set，秒级即时）
     * ordered   = 订单已异步落库（DB）
     */
    Result orderState(Long productId);

    /**
     * 我的订单列表（带商品标题、商户名、金额，新订单在前）
     */
    Result queryMyOrders();
}
