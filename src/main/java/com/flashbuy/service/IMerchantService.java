package com.flashbuy.service;

import com.flashbuy.dto.Result;
import com.flashbuy.entity.Merchant;
import com.baomidou.mybatisplus.spring.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IMerchantService extends IService<Merchant> {

    Result queryById(Long id);

    Result update(Merchant merchant);

    Result queryMerchantByType(Integer typeId, Integer current, Double x, Double y, String sortBy);
}
