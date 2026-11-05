package com.zhaojin.flashbuy.controller;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.zhaojin.flashbuy.dto.LoginFormDTO;
import com.zhaojin.flashbuy.dto.Result;
import com.zhaojin.flashbuy.dto.UserDTO;
import com.zhaojin.flashbuy.dto.UserProfileDTO;
import com.zhaojin.flashbuy.entity.User;
import com.zhaojin.flashbuy.entity.UserInfo;
import com.zhaojin.flashbuy.service.IUserInfoService;
import com.zhaojin.flashbuy.service.IUserService;
import com.zhaojin.flashbuy.utils.JwtUtil;
import com.zhaojin.flashbuy.utils.UserHolder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.zhaojin.flashbuy.utils.RedisConstants.LOGIN_JWT_DENY_KEY;
import static com.zhaojin.flashbuy.utils.RedisConstants.LOGIN_REFRESH_KEY;

/**
 * <p>
 * 前端控制器
 * </p>
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private IUserService userService;

    @Resource
    private IUserInfoService userInfoService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private JwtUtil jwtUtil;

    /**
     * 发送手机验证码（同手机号 60 秒内限流 1 次）
     */
    @PostMapping("code")
    public Result sendCode(@RequestParam("phone") @NotBlank(message = "手机号不能为空")
                           @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确") String phone,
                           HttpSession session) {
        // 发送短信验证码并保存验证码
        return userService.sendCode(phone, session);
    }

    /**
     * 登录功能：校验通过后签发双 Token（accessToken + refreshToken）
     */
    @PostMapping("/login")
    public Result login(@Valid @RequestBody LoginFormDTO loginForm, HttpSession session){
        // 实现登录功能
        return userService.login(loginForm, session);
    }

    /**
     * 刷新登录：用 refreshToken 换新的双 Token（一次性轮换，防重放）
     */
    @PostMapping("/refresh")
    public Result refresh(@RequestBody Map<String, String> body) {
        return userService.refreshLogin(body.get("refreshToken"));
    }

    /**
     * 登出：accessToken 的 jti 加入黑名单（TTL=剩余有效期），refreshToken 直接删除
     */
    @PostMapping("/logout")
    public Result logout(@RequestHeader(value = "authorization", required = false) String token,
                         @RequestBody(required = false) Map<String, String> body){
        if (StrUtil.isNotBlank(token)) {
            Map<String, Object> payload = jwtUtil.parse(token);
            if (payload != null) {
                long ttl = jwtUtil.remainingMillis(payload);
                if (ttl > 0) {
                    stringRedisTemplate.opsForValue().set(LOGIN_JWT_DENY_KEY + jwtUtil.getJti(payload),
                            "1", ttl, TimeUnit.MILLISECONDS);
                }
            }
        }
        if (body != null && StrUtil.isNotBlank(body.get("refreshToken"))) {
            stringRedisTemplate.delete(LOGIN_REFRESH_KEY + body.get("refreshToken"));
        }
        return Result.ok();
    }

    @GetMapping("/me")
    public Result me(){
        // 以数据库为准返回当前用户，保证资料修改后昵称/头像立即生效
        UserDTO current = UserHolder.getUser();
        if (current == null) {
            return Result.ok();
        }
        User user = userService.getById(current.getId());
        if (user == null) {
            return Result.ok(current);
        }
        return Result.ok(BeanUtil.copyProperties(user, UserDTO.class));
    }

    /**
     * 更新当前登录用户的昵称与个人资料
     */
    @PutMapping("/profile")
    public Result updateProfile(@Valid @RequestBody UserProfileDTO dto) {
        return userService.updateProfile(dto);
    }

    @GetMapping("/info/{id}")
    public Result info(@PathVariable("id") Long userId){
        // 查询详情
        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            // 没有详情，应该是第一次查看详情
            return Result.ok();
        }
        info.setCreateTime(null);
        info.setUpdateTime(null);
        // 返回
        return Result.ok(info);
    }

    @GetMapping("/{id}")
    public Result queryUserById(@PathVariable("id") Long userId){
        // 查询详情
        User user = userService.getById(userId);
        if (user == null) {
            return Result.ok();
        }
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 返回
        return Result.ok(userDTO);
    }

    @PostMapping("/sign")
    public Result sign(){
        return userService.sign();
    }

    @GetMapping("/sign/count")
    public Result signCount(){
        return userService.signCount();
    }

    /**
     * 本月累计签到天数（BITCOUNT）
     */
    @GetMapping("/sign/month-count")
    public Result signMonthCount(){
        return userService.signMonthCount();
    }
}
