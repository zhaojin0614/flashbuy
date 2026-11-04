package com.flashbuy.controller;


import com.flashbuy.dto.Result;
import com.flashbuy.entity.PostComment;
import com.flashbuy.service.IPostCommentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;

/**
 * <p>
 *  晒单评论前端控制器（课程留白部分，自行实现）
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/post-comments")
public class PostCommentController {

    @Resource
    private IPostCommentService postCommentService;

    /**
     * 发表评论（登录态）：body 传 postId + content
     */
    @PostMapping
    public Result savePostComment(@RequestBody PostComment comment) {
        return postCommentService.saveComment(comment);
    }

    /**
     * 分页查询某晒单的评论列表（新评论在前，带用户昵称头像）
     */
    @GetMapping("/of/{postId}")
    public Result queryPostComments(@PathVariable("postId") Long postId,
                                    @RequestParam(value = "current", defaultValue = "1") Integer current) {
        return postCommentService.queryPostComments(postId, current);
    }
}
