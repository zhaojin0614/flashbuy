package com.zhaojin.flashbuy.service;

import com.zhaojin.flashbuy.dto.Result;
import com.zhaojin.flashbuy.entity.Product;
import com.baomidou.mybatisplus.spring.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 */
public interface IProductService extends IService<Product> {

    Result queryProductOfMerchant(Long merchantId);

    void addProductRelease(Product product);
}
