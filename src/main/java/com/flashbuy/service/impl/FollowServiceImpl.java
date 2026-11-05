package com.flashbuy.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.flashbuy.dto.Result;
import com.flashbuy.dto.UserDTO;
import com.flashbuy.entity.Follow;
import com.flashbuy.mapper.FollowMapper;
import com.flashbuy.service.IFollowService;
import com.flashbuy.service.IUserService;
import com.flashbuy.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        if (followUserId == null) {
            return Result.fail("用户不存在");
        }
        if (followUserId.equals(userId)) {
            return Result.fail("不能关注自己");
        }
        if (userService.getById(followUserId) == null) {
            return Result.fail("用户不存在");
        }
        String key = "follows:" + userId;
        Long count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        // 2.判断到底是关注还是取关
        if (isFollow) {
            // 2.1.关注：数据库去重，Redis Set 天然幂等
            if (count == 0) {
                Follow follow = new Follow();
                follow.setUserId(userId);
                follow.setFollowUserId(followUserId);
                save(follow);
            }
            stringRedisTemplate.opsForSet().add(key, followUserId.toString());
        } else {
            // 2.2.取关：数据库和 Redis 都删除
            if (count > 0) {
                remove(new QueryWrapper<Follow>()
                        .eq("user_id", userId).eq("follow_user_id", followUserId));
            }
            stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.查询是否关注 select count(*) from tb_follow where user_id = ? and follow_user_id = ?
        Long count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        // 3.判断
        return Result.ok(count > 0);
    }

    @Override
    public Result followCommons(Long id) {
        // 1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 2.取双方关注集合（Redis 为缓存，规模不一致时从 DB 重建）
        Set<String> mine = new HashSet<>(getFollowIds(userId));
        Set<String> other = getFollowIds(id);
        mine.retainAll(other);
        if (mine.isEmpty()) {
            // 无交集
            return Result.ok(Collections.emptyList());
        }
        // 3.解析id集合
        List<Long> ids = mine.stream().map(Long::valueOf).collect(Collectors.toList());
        // 4.查询用户
        List<UserDTO> users = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }

    /**
     * 获取某用户的关注集合。Redis Set 只是缓存：
     * 当 Redis 重启或数据被清理时，用数据库重建，避免共同关注“凭空消失”。
     */
    private Set<String> getFollowIds(Long userId) {
        String key = "follows:" + userId;
        Set<String> members = stringRedisTemplate.opsForSet().members(key);
        Long dbCount = query().eq("user_id", userId).count();
        if (members != null && members.size() == dbCount.intValue()) {
            return members;
        }
        List<Follow> follows = query().eq("user_id", userId).list();
        Set<String> ids = follows.stream()
                .map(follow -> follow.getFollowUserId().toString())
                .collect(Collectors.toSet());
        stringRedisTemplate.delete(key);
        if (!ids.isEmpty()) {
            stringRedisTemplate.opsForSet().add(key, ids.toArray(new String[0]));
        }
        return ids;
    }

    @Override
    public Result followCount(Long userId) {
        Long fans = query().eq("follow_user_id", userId).count();
        Long followee = query().eq("user_id", userId).count();
        Map<String, Long> counts = new HashMap<>();
        counts.put("fans", fans);
        counts.put("followee", followee);
        return Result.ok(counts);
    }
}
