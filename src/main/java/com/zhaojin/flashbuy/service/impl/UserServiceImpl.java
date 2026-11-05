package com.zhaojin.flashbuy.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zhaojin.flashbuy.dto.LoginFormDTO;
import com.zhaojin.flashbuy.dto.Result;
import com.zhaojin.flashbuy.dto.UserDTO;
import com.zhaojin.flashbuy.dto.UserProfileDTO;
import com.zhaojin.flashbuy.entity.User;
import com.zhaojin.flashbuy.entity.UserInfo;
import com.zhaojin.flashbuy.mapper.UserMapper;
import com.zhaojin.flashbuy.service.IUserInfoService;
import com.zhaojin.flashbuy.service.IUserService;
import com.zhaojin.flashbuy.utils.JwtUtil;
import com.zhaojin.flashbuy.utils.RateLimiter;
import com.zhaojin.flashbuy.utils.RegexUtils;
import com.zhaojin.flashbuy.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.zhaojin.flashbuy.utils.RedisConstants.*;
import static com.zhaojin.flashbuy.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private JwtUtil jwtUtil;

    @Resource
    private RateLimiter rateLimiter;

    @Resource
    private IUserInfoService userInfoService;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            //2.不符合：返回错误信息
            return Result.fail("手机号格式错误！");
        }
        //1.5.滑动窗口限流：同一手机号 60 秒内最多发 1 次，防止验证码轰炸/短信费用被打爆
        if(!rateLimiter.isAllowed("rl:code:" + phone, 60_000L, 1)){
            return Result.fail("发送太频繁，请稍后再试");
        }
        //3.符合，生成验证码保存到 redis
        String code=RandomUtil.randomNumbers(6);
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);

        //4.发送验证码
        log.info("发送验证码成功！验证码为：{}",code);
        //5.返回结果
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号
        String phone=loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            //2.不符合：返回错误信息
            return Result.fail("手机号格式错误！");
        }
        //1.5.滑动窗口限流：同一手机号 60 秒内最多尝试登录 5 次，防暴力猜验证码
        if(!rateLimiter.isAllowed("rl:login:" + phone, 60_000L, 5)){
            return Result.fail("尝试过于频繁，请稍后再试");
        }
        //2.校验验证码，从redis获取
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);
        String code=loginForm.getCode();
        if (cacheCode==null||!cacheCode.equals(code))
            return Result.fail("验证码错误！");
        //3.查询用户
        User user=query().eq("phone",phone).one();
        //4.不存在，创建新用户并保存
        if(user==null){
            user=new User();
            user.setPhone(phone);
            user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
            save(user);
        }
        //5.保存用户到redis
        //6.签发双 Token（P1 认证升级：JWT 无状态 accessToken + Redis 轮换 refreshToken）
        //6.1.accessToken：JWT 携带 uid/nick/icon，2 小时有效，无状态校验
        String accessToken = jwtUtil.createAccessToken(user.getId(), user.getNickName(), user.getIcon());
        //6.2.refreshToken：随机串存 Redis，7 天有效，用于 accessToken 过期后换新（一次性轮换）
        String refreshToken = UUID.randomUUID().toString(true);
        stringRedisTemplate.opsForValue().set(LOGIN_REFRESH_KEY + refreshToken,
                user.getId().toString(), LOGIN_REFRESH_TTL, TimeUnit.HOURS);

        //7.返回双 Token
        Map<String, String> tokens = new HashMap<>();
        tokens.put("accessToken", accessToken);
        tokens.put("refreshToken", refreshToken);
        return Result.ok(tokens);
    }

    @Override
    public Result refreshLogin(String refreshToken) {
        //1.校验 refreshToken 是否有效（Redis 中存在）
        String uid = stringRedisTemplate.opsForValue().get(LOGIN_REFRESH_KEY + refreshToken);
        if (uid == null) {
            return Result.fail("登录已过期，请重新登录");
        }
        //2.查用户（昵称/头像要进新 accessToken）
        User user = getById(Long.valueOf(uid));
        if (user == null) {
            return Result.fail("用户不存在");
        }
        //3.轮换 refreshToken：删除旧的，签发新的（防重放——被盗的旧 refreshToken 立即失效）
        stringRedisTemplate.delete(LOGIN_REFRESH_KEY + refreshToken);
        String newAccessToken = jwtUtil.createAccessToken(user.getId(), user.getNickName(), user.getIcon());
        String newRefreshToken = UUID.randomUUID().toString(true);
        stringRedisTemplate.opsForValue().set(LOGIN_REFRESH_KEY + newRefreshToken,
                uid, LOGIN_REFRESH_TTL, TimeUnit.HOURS);
        //4.返回新双 Token
        Map<String, String> tokens = new HashMap<>();
        tokens.put("accessToken", newAccessToken);
        tokens.put("refreshToken", newRefreshToken);
        return Result.ok(tokens);
    }

    @Override
    public Result sign() {
        //1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //2.获取日期
        LocalDateTime now = LocalDateTime.now();
        //3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        //4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //5.写入Redis SETBIT key offset 1
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        //1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //2.获取日期
        LocalDateTime now = LocalDateTime.now();
        //3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        //4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //5.获取本月截止今天为止的所有签到记录，返回一个十进制数字 BITFIELD sign:5:202203 GET u14 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if (result == null || result.isEmpty()) {
            //没有任何签到结果
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }
        //6.循环遍历，从最低位开始统计连续签到的天数
        int count = 0;
        while (true) {
            //6.1.让这个数字与1做与运算，得到数字的最后一个bit位
            if ((num & 1) == 0) {
                //为0，说明未签到，结束
                break;
            } else {
                //不为0，说明已签到，计数器+1
                count++;
            }
            //把数字右移一位，抛弃最后一个bit位，继续下一个bit位
            num >>>= 1;
        }
        return Result.ok(count);
    }

    @Override
    public Result signMonthCount() {
        //1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //2.拼接本月 BitMap 的 key：sign:{userId}:{yyyyMM}
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        //3.BITCOUNT 统计整个 BitMap 中 1 的个数 = 本月累计签到天数
        //  与连续签到的区别：累计不看顺序，全月签到过的每一天都计入
        Long count = stringRedisTemplate.execute((org.springframework.data.redis.core.RedisCallback<Long>) con ->
                con.stringCommands().bitCount(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return Result.ok(count == null ? 0 : count);
    }

    @Override
    @Transactional
    public Result updateProfile(UserProfileDTO dto) {
        Long userId = UserHolder.getUser().getId();
        User user = getById(userId);
        if (user == null) {
            return Result.fail("用户不存在");
        }
        boolean userChanged = false;
        if (StrUtil.isNotBlank(dto.getNickName()) && !dto.getNickName().equals(user.getNickName())) {
            user.setNickName(dto.getNickName());
            userChanged = true;
        }
        if (userChanged) {
            updateById(user);
        }

        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            info = new UserInfo().setUserId(userId);
        }
        if (dto.getIntroduce() != null) {
            info.setIntroduce(dto.getIntroduce());
        }
        if (dto.getCity() != null) {
            info.setCity(dto.getCity());
        }
        if (dto.getGender() != null) {
            info.setGender(dto.getGender());
        }
        if (dto.getBirthday() != null) {
            info.setBirthday(dto.getBirthday());
        }
        userInfoService.saveOrUpdate(info);
        return Result.ok();
    }
}
