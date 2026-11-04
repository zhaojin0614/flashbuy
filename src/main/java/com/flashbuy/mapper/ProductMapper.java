package com.flashbuy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flashbuy.entity.Product;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface ProductMapper extends BaseMapper<Product> {

    List<Product> queryProductOfMerchant(@Param("merchantId") Long merchantId);
}
