package com.flashbuy.service.impl;

import com.flashbuy.entity.ProductRelease;
import com.flashbuy.mapper.ProductReleaseMapper;
import com.flashbuy.service.IProductReleaseService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 秒杀优惠券表，与优惠券是一对一关系 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2022-01-04
 */
@Service
public class ProductReleaseServiceImpl extends ServiceImpl<ProductReleaseMapper, ProductRelease> implements IProductReleaseService {

}
