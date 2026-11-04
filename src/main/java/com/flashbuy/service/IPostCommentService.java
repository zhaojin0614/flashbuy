package com.flashbuy.service;

import com.flashbuy.dto.Result;
import com.flashbuy.entity.PostComment;
import com.baomidou.mybatisplus.spring.service.IService;

/**
 * <p>
 *
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IPostCommentService extends IService<PostComment> {

    /**
     * 发表评论（登录态），并累加笔记评论数
     */
    Result saveComment(PostComment comment);

    /**
     * 分页查询某晒单的评论列表（新评论在前，带用户昵称头像）
     */
    Result queryPostComments(Long postId, Integer current);
}
