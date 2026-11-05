package com.zhaojin.flashbuy.service;

import com.zhaojin.flashbuy.dto.Result;
import com.zhaojin.flashbuy.entity.MerchantType;
import com.baomidou.mybatisplus.spring.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 */
public interface IMerchantTypeService extends IService<MerchantType> {

    Result queryTypeList();
}
