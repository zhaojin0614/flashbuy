package com.flashbuy.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.flashbuy.dto.Result;
import com.flashbuy.dto.ScrollResult;
import com.flashbuy.dto.UserDTO;
import com.flashbuy.entity.Merchant;
import com.flashbuy.entity.Post;
import com.flashbuy.entity.Follow;
import com.flashbuy.entity.User;
import com.flashbuy.mapper.PostMapper;
import com.flashbuy.service.IMerchantService;
import com.flashbuy.service.IPostService;
import com.flashbuy.service.IFollowService;
import com.flashbuy.service.IUserService;
import com.flashbuy.utils.SystemConstants;
import com.flashbuy.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.flashbuy.utils.RedisConstants.POST_LIKED_KEY;
import static com.flashbuy.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class PostServiceImpl extends ServiceImpl<PostMapper, Post> implements IPostService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    @Resource
    private IMerchantService merchantService;

    @Override
    public Result queryHotPost(Integer current) {
        // 根据用户查询
        Page<Post> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Post> records = page.getRecords();
        // 查询作者、商户与点赞状态
        enrichPosts(records);
        return Result.ok(records);
    }

    @Override
    public Result queryPostById(Long id) {
        // 1.查询post
        Post post = getById(id);
        if (post == null) {
            return Result.fail("笔记不存在！");
        }
        // 2.查询post有关的用户、商户与点赞状态
        enrichPosts(Collections.singletonList(post));
        return Result.ok(post);
    }

    private void isPostLiked(Post post) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            // 用户未登录，无需查询是否点赞
            return;
        }
        Long userId = user.getId();
        // 2.判断当前登录用户是否已经点赞
        String key = POST_LIKED_KEY + post.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        post.setIsLike(score != null);
    }

    @Override
    public Result likePost(Long id) {
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.判断当前登录用户是否已经点赞
        String key = POST_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
            // 3.如果未点赞，可以点赞
            // 3.1.数据库点赞数 + 1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            // 3.2.保存用户到Redis的zset集合 zadd key value score
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            // 4.如果已点赞，取消点赞
            // 4.1.数据库点赞数 -1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            // 4.2.把用户从Redis的zset集合移除
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryPostLikes(Long id) {
        String key = POST_LIKED_KEY + id;
        // 1.查询top5的点赞用户 zrange key 0 4
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 2.解析出其中的用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        // 3.根据用户id查询用户 WHERE id IN ( 5 , 1 ) ORDER BY FIELD(id, 5, 1)
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        // 4.返回
        return Result.ok(userDTOS);
    }

    @Override
    public Result savePost(Post post) {
        // 1.校验关联商户
        if (post.getMerchantId() == null) {
            return Result.fail("请选择关联商户");
        }
        Merchant merchant = merchantService.getById(post.getMerchantId());
        if (merchant == null) {
            return Result.fail("关联商户不存在");
        }
        // 2.获取登录用户
        UserDTO user = UserHolder.getUser();
        post.setUserId(user.getId());
        // 3.保存晒单
        boolean isSuccess = save(post);
        if (!isSuccess) {
            return Result.fail("新增晒单失败!");
        }
        // 4.查询笔记作者的所有粉丝 select * from tb_follow where follow_user_id = ?
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        // 5.推送晒单id给所有粉丝
        for (Follow follow : follows) {
            // 5.1.获取粉丝id
            Long userId = follow.getUserId();
            // 5.2.推送
            String key = FEED_KEY + userId;
            stringRedisTemplate.opsForZSet().add(key, post.getId().toString(), System.currentTimeMillis());
        }
        // 6.返回id
        return Result.ok(post.getId());
    }

    @Override
    public Result queryPostByUserId(Long userId, Integer current) {
        Page<Post> page = query()
                .eq("user_id", userId)
                .orderByDesc("create_time")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Post> records = page.getRecords();
        enrichPosts(records);
        return Result.ok(records);
    }

    @Override
    public Result queryPostByMerchant(Long merchantId, Integer current) {
        Page<Post> page = query()
                .eq("merchant_id", merchantId)
                .orderByDesc("create_time")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Post> records = page.getRecords();
        enrichPosts(records);
        return Result.ok(records);
    }

    @Override
    public Result queryPostOfFollow(Long max, Integer offset) {
        // 1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 2.查询收件箱 ZREVRANGEBYSCORE key Max Min LIMIT offset count
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        // 3.非空判断
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        // 4.解析数据：postId、minTime（时间戳）、offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            // 4.1.获取id
            ids.add(Long.valueOf(tuple.getValue()));
            // 4.2.获取分数(时间戳)
            long time = tuple.getScore().longValue();
            if (time == minTime) {
                os++;
            } else {
                minTime = time;
                os = 1;
            }
        }
        // 5.根据id查询post
        String idStr = StrUtil.join(",", ids);
        List<Post> posts = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        enrichPosts(posts);
        // 6.封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(posts);
        r.setOffset(os);
        r.setMinTime(minTime);
        return Result.ok(r);
    }

    private void enrichPosts(List<Post> posts) {
        if (posts == null || posts.isEmpty()) {
            return;
        }
        // 1.批量查询作者
        List<Long> userIds = posts.stream().map(Post::getUserId)
                .filter(Objects::nonNull).distinct().collect(Collectors.toList());
        Map<Long, User> userMap = userIds.isEmpty() ? Collections.emptyMap()
                : userService.listByIds(userIds).stream()
                        .collect(Collectors.toMap(User::getId, user -> user));
        // 2.批量查询关联商户
        List<Long> merchantIds = posts.stream().map(Post::getMerchantId)
                .filter(Objects::nonNull).distinct().collect(Collectors.toList());
        Map<Long, Merchant> merchantMap = merchantIds.isEmpty() ? Collections.emptyMap()
                : merchantService.listByIds(merchantIds).stream()
                        .collect(Collectors.toMap(Merchant::getId, merchant -> merchant));
        // 3.回填展示字段与点赞状态
        for (Post post : posts) {
            User user = userMap.get(post.getUserId());
            if (user != null) {
                post.setName(user.getNickName());
                post.setIcon(user.getIcon());
            }
            Merchant merchant = post.getMerchantId() == null ? null : merchantMap.get(post.getMerchantId());
            if (merchant != null) {
                post.setMerchantName(merchant.getName());
            }
            isPostLiked(post);
        }
    }
}
