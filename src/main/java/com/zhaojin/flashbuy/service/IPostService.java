package com.zhaojin.flashbuy.service;

import com.zhaojin.flashbuy.dto.Result;
import com.zhaojin.flashbuy.entity.Post;
import com.baomidou.mybatisplus.spring.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 */
public interface IPostService extends IService<Post> {

    Result queryHotPost(Integer current);

    Result queryPostById(Long id);

    Result likePost(Long id);

    Result queryPostLikes(Long id);

    Result savePost(Post post);

    Result queryPostOfFollow(Long max, Integer offset);

    Result queryPostByUserId(Long userId, Integer current);

    Result queryPostByMerchant(Long merchantId, Integer current);
}
