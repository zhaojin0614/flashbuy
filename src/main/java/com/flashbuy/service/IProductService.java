package com.flashbuy.service;

import com.flashbuy.dto.Result;
import com.flashbuy.entity.Product;
import com.baomidou.mybatisplus.spring.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IProductService extends IService<Product> {

    Result queryProductOfMerchant(Long merchantId);

    void addProductRelease(Product product);
}
