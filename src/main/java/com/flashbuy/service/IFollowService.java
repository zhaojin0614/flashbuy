package com.flashbuy.service;

import com.flashbuy.dto.Result;
import com.flashbuy.entity.Follow;
import com.baomidou.mybatisplus.spring.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IFollowService extends IService<Follow> {

    Result follow(Long followUserId, Boolean isFollow);

    Result isFollow(Long followUserId);

    Result followCommons(Long id);

    Result followCount(Long userId);

    Result fans(Long userId);

    Result followees(Long userId);
}
