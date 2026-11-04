package com.flashbuy.controller;


import com.flashbuy.dto.Result;
import com.flashbuy.service.IOrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
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
}
