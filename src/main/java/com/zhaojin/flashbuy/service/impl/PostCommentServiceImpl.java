package com.zhaojin.flashbuy.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zhaojin.flashbuy.dto.Result;
import com.zhaojin.flashbuy.dto.UserDTO;
import com.zhaojin.flashbuy.entity.PostComment;
import com.zhaojin.flashbuy.entity.User;
import com.zhaojin.flashbuy.mapper.PostCommentMapper;
import com.zhaojin.flashbuy.service.IPostCommentService;
import com.zhaojin.flashbuy.service.IPostService;
import com.zhaojin.flashbuy.service.IUserService;
import com.zhaojin.flashbuy.utils.SystemConstants;
import com.zhaojin.flashbuy.utils.UserHolder;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <p>
 *  晒单评论服务实现（课程留白部分，自行实现）
 * </p>
 */
@Service
public class PostCommentServiceImpl extends ServiceImpl<PostCommentMapper, PostComment> implements IPostCommentService {

    @Resource
    private IPostService postService;

    @Resource
    private IUserService userService;

    @Override
    public Result saveComment(PostComment comment) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        comment.setUserId(user.getId());
        // 2.补充默认值：一级评论 parentId/answerId 为 0，防 NOT NULL 列报错
        if (comment.getParentId() == null) {
            comment.setParentId(0L);
        }
        if (comment.getAnswerId() == null) {
            comment.setAnswerId(0L);
        }
        comment.setLiked(0);
        // 3.保存评论
        boolean ok = save(comment);
        if (!ok) {
            return Result.fail("评论失败！");
        }
        // 4.累加笔记评论数（列表/详情展示用）
        postService.update()
                .setSql("comments = comments + 1")
                .eq("id", comment.getPostId())
                .update();
        return Result.ok();
    }

    @Override
    public Result queryPostComments(Long postId, Integer current) {
        // 1.分页查询该晒单的评论（新评论在前）
        Page<PostComment> page = query()
                .eq("post_id", postId)
                .orderByDesc("create_time")
                .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
        List<PostComment> list = page.getRecords();
        if (list.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 2.批量查询评论用户，组装昵称头像（两段查询避免 N+1 联表）
        Set<Long> userIds = list.stream().map(PostComment::getUserId).collect(Collectors.toSet());
        Map<Long, User> userMap = userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        list.forEach(c -> {
            User u = userMap.get(c.getUserId());
            if (u != null) {
                c.setName(u.getNickName());
                c.setIcon(u.getIcon());
            }
        });
        return Result.ok(list);
    }
}
