package com.flashbuy.service;

import com.flashbuy.dto.Result;
import com.flashbuy.entity.Post;
import com.baomidou.mybatisplus.spring.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IPostService extends IService<Post> {

    Result queryHotPost(Integer current);

    Result queryPostById(Long id);

    Result likePost(Long id);

    Result queryPostLikes(Long id);

    Result savePost(Post post);

    Result queryPostOfFollow(Long max, Integer offset);
}
