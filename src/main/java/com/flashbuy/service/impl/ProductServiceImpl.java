package com.flashbuy.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.flashbuy.dto.Result;
import com.flashbuy.entity.Product;
import com.flashbuy.mapper.ProductMapper;
import com.flashbuy.entity.ProductRelease;
import com.flashbuy.service.IProductReleaseService;
import com.flashbuy.service.IProductService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import java.util.List;

import static com.flashbuy.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ProductServiceImpl extends ServiceImpl<ProductMapper, Product> implements IProductService {

    @Resource
    private IProductReleaseService seckillProductService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @PostConstruct
    public void initSeckillStock() {
        // 补齐历史秒杀券的Redis库存，避免lua脚本读取不到库存key
        List<ProductRelease> seckillProducts = seckillProductService.list();
        for (ProductRelease seckillProduct : seckillProducts) {
            String stockKey = SECKILL_STOCK_KEY + seckillProduct.getProductId();
            if (Boolean.FALSE.equals(stringRedisTemplate.hasKey(stockKey))) {
                stringRedisTemplate.opsForValue().set(stockKey, seckillProduct.getStock().toString());
            }
        }
    }

    @Override
    public Result queryProductOfMerchant(Long merchantId) {
        // 查询优惠券信息
        List<Product> products = getBaseMapper().queryProductOfMerchant(merchantId);
        // 返回结果
        return Result.ok(products);
    }

    @Override
    @Transactional
    public void addProductRelease(Product product) {
        // 保存优惠券
        save(product);
        // 保存秒杀信息
        ProductRelease seckillProduct = new ProductRelease();
        seckillProduct.setProductId(product.getId());
        seckillProduct.setStock(product.getStock());
        seckillProduct.setBeginTime(product.getBeginTime());
        seckillProduct.setEndTime(product.getEndTime());
        seckillProductService.save(seckillProduct);
        // 保存秒杀库存到Redis中，供lua脚本预扣库存使用
        stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + product.getId(), product.getStock().toString());
    }
}
