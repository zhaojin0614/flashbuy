package com.zhaojin.flashbuy.service;

import com.zhaojin.flashbuy.dto.Result;
import com.zhaojin.flashbuy.entity.Follow;
import com.baomidou.mybatisplus.spring.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 */
public interface IFollowService extends IService<Follow> {

    Result follow(Long followUserId, Boolean isFollow);

    Result isFollow(Long followUserId);

    Result followCommons(Long id);

    Result followCount(Long userId);

    Result fans(Long userId);

    Result followees(Long userId);
}
