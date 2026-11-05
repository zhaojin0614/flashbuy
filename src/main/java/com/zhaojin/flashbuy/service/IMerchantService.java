package com.zhaojin.flashbuy.service;

import com.zhaojin.flashbuy.dto.Result;
import com.zhaojin.flashbuy.entity.Merchant;
import com.baomidou.mybatisplus.spring.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 */
public interface IMerchantService extends IService<Merchant> {

    Result queryById(Long id);

    Result update(Merchant merchant);

    Result queryMerchantByType(Integer typeId, Integer current, Double x, Double y, String sortBy);
}
