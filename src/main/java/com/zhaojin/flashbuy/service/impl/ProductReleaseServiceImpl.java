package com.zhaojin.flashbuy.service.impl;

import com.zhaojin.flashbuy.entity.ProductRelease;
import com.zhaojin.flashbuy.mapper.ProductReleaseMapper;
import com.zhaojin.flashbuy.service.IProductReleaseService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 秒杀限量商品表，与限量商品是一对一关系 服务实现类
 * </p>
 *
 * @since 2022-01-04
 */
@Service
public class ProductReleaseServiceImpl extends ServiceImpl<ProductReleaseMapper, ProductRelease> implements IProductReleaseService {

}
