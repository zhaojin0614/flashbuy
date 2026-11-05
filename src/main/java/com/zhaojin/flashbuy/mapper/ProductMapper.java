package com.zhaojin.flashbuy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhaojin.flashbuy.entity.Product;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 */
public interface ProductMapper extends BaseMapper<Product> {

    List<Product> queryProductOfMerchant(@Param("merchantId") Long merchantId);
}
