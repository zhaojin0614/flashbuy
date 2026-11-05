package com.zhaojin.flashbuy.controller;


import com.zhaojin.flashbuy.dto.Result;
import com.zhaojin.flashbuy.entity.Product;
import com.zhaojin.flashbuy.service.IProductService;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 */
@RestController
@RequestMapping("/product")
public class ProductController {

    @Resource
    private IProductService productService;

    /**
     * 新增普通券
     * @param product 限量商品信息
     * @return 限量商品id
     */
    @PostMapping
    public Result addProduct(@RequestBody Product product) {
        productService.save(product);
        return Result.ok(product.getId());
    }

    /**
     * 新增秒杀券
     * @param product 限量商品信息，包含秒杀信息
     * @return 限量商品id
     */
    @PostMapping("seckill")
    public Result addProductRelease(@RequestBody Product product) {
        productService.addProductRelease(product);
        return Result.ok(product.getId());
    }

    /**
     * 查询商户的限量商品列表
     * @param merchantId 商户id
     * @return 限量商品列表
     */
    @GetMapping("/list/{merchantId}")
    public Result queryProductOfMerchant(@PathVariable("merchantId") Long merchantId) {
       return productService.queryProductOfMerchant(merchantId);
    }
}
