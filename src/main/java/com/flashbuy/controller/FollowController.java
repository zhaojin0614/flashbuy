package com.flashbuy.controller;


import com.flashbuy.dto.Result;
import com.flashbuy.service.IFollowService;
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
@RequestMapping("/follow")
public class FollowController {

    @Resource
    private IFollowService followService;

    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable("id") Long followUserId, @PathVariable("isFollow") Boolean isFollow) {
        return followService.follow(followUserId, isFollow);
    }

    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable("id") Long followUserId) {
        return followService.isFollow(followUserId);
    }

    @GetMapping("/common/{id}")
    public Result followCommons(@PathVariable("id") Long id) {
        return followService.followCommons(id);
    }

    /**
     * 用户主页的粉丝数 / 关注数（从 tb_follow 实时统计）
     */
    @GetMapping("/count/{id}")
    public Result followCount(@PathVariable("id") Long id) {
        return followService.followCount(id);
    }

    /**
     * 某用户的粉丝列表
     */
    @GetMapping("/fans/{id}")
    public Result fans(@PathVariable("id") Long id) {
        return followService.fans(id);
    }

    /**
     * 某用户关注的人列表
     */
    @GetMapping("/followees/{id}")
    public Result followees(@PathVariable("id") Long id) {
        return followService.followees(id);
    }
}
