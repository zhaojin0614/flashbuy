package com.zhaojin.flashbuy.service;

import com.zhaojin.flashbuy.dto.Result;
import com.zhaojin.flashbuy.entity.PostComment;
import com.baomidou.mybatisplus.spring.service.IService;

/**
 * <p>
 *
 * </p>
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
