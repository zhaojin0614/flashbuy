package com.flashbuy.controller;


import com.flashbuy.dto.Result;
import com.flashbuy.entity.Product;
import com.flashbuy.service.IProductService;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/product")
public class ProductController {

    @Resource
    private IProductService productService;

    /**
     * 新增普通券
     * @param product 优惠券信息
     * @return 优惠券id
     */
    @PostMapping
    public Result addProduct(@RequestBody Product product) {
        productService.save(product);
        return Result.ok(product.getId());
    }

    /**
     * 新增秒杀券
     * @param product 优惠券信息，包含秒杀信息
     * @return 优惠券id
     */
    @PostMapping("seckill")
    public Result addProductRelease(@RequestBody Product product) {
        productService.addProductRelease(product);
        return Result.ok(product.getId());
    }

    /**
     * 查询店铺的优惠券列表
     * @param merchantId 店铺id
     * @return 优惠券列表
     */
    @GetMapping("/list/{merchantId}")
    public Result queryProductOfMerchant(@PathVariable("merchantId") Long merchantId) {
       return productService.queryProductOfMerchant(merchantId);
    }
}
