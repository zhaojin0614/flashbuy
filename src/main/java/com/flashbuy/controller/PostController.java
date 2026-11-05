package com.flashbuy.controller;


import com.flashbuy.dto.Result;
import com.flashbuy.entity.Post;
import com.flashbuy.service.IPostService;
import com.flashbuy.utils.UserHolder;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/post")
public class PostController {

    @Resource
    private IPostService postService;

    @PostMapping
    public Result savePost(@RequestBody Post post) {
        return postService.savePost(post);
    }

    @PutMapping("/like/{id}")
    public Result likePost(@PathVariable("id") Long id) {
        return postService.likePost(id);
    }

    @GetMapping("/of/me")
    public Result queryMyPost(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return postService.queryPostByUserId(UserHolder.getUser().getId(), current);
    }

    @GetMapping("/hot")
    public Result queryHotPost(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return postService.queryHotPost(current);
    }

    @GetMapping("/{id}")
    public Result queryPostById(@PathVariable("id") Long id) {
        return postService.queryPostById(id);
    }

    @GetMapping("/likes/{id}")
    public Result queryPostLikes(@PathVariable("id") Long id) {
        return postService.queryPostLikes(id);
    }

    @GetMapping("/of/user")
    public Result queryPostByUserId(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam("id") Long id) {
        return postService.queryPostByUserId(id, current);
    }

    @GetMapping("/of/merchant")
    public Result queryPostByMerchant(
            @RequestParam("merchantId") Long merchantId,
            @RequestParam(value = "current", defaultValue = "1") Integer current) {
        return postService.queryPostByMerchant(merchantId, current);
    }

    @GetMapping("/of/follow")
    public Result queryPostOfFollow(
            @RequestParam("lastId") Long max, @RequestParam(value = "offset", defaultValue = "0") Integer offset) {
        return postService.queryPostOfFollow(max, offset);
    }
}
