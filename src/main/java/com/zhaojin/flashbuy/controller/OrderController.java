package com.zhaojin.flashbuy.controller;


import com.zhaojin.flashbuy.dto.Result;
import com.zhaojin.flashbuy.service.IOrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 *  前端控制器
 * </p>
 */
@RestController
@RequestMapping("/order")
public class OrderController {
    @Autowired
    private IOrderService orderService;

    @PostMapping("seckill/{id}")
    public Result seckillProduct(@PathVariable("id") Long productId) {
        return orderService.seckillProduct(productId);
    }

    /**
     * 当前用户的抢购状态：前端据此恢复按钮（已抢到 / 创建中 / 可抢购）
     */
    @GetMapping("state/{id}")
    public Result orderState(@PathVariable("id") Long productId) {
        return orderService.orderState(productId);
    }

    /**
     * 我的订单列表（带商品标题、商户名、金额，新订单在前）
     */
    @GetMapping("of/me")
    public Result queryMyOrders() {
        return orderService.queryMyOrders();
    }

    /**
     * 演示支付：待支付订单 CAS 1→2；支付后延迟关单会跳过。
     */
    @PostMapping("pay/{id}")
    public Result payOrder(@PathVariable("id") Long orderId,
                           @RequestParam(value = "payType", defaultValue = "1") Integer payType) {
        return orderService.payOrder(orderId, payType);
    }
}
