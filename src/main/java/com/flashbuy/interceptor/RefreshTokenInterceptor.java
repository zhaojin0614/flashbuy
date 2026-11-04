package com.flashbuy.interceptor;

import cn.hutool.core.util.StrUtil;
import com.flashbuy.dto.UserDTO;
import com.flashbuy.utils.JwtUtil;
import com.flashbuy.utils.UserHolder;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.annotation.Resource;
import java.util.Map;

import static com.flashbuy.utils.RedisConstants.LOGIN_JWT_DENY_KEY;

/**
 * JWT 解析拦截器：从 accessToken 中还原用户到 ThreadLocal（P1 认证升级）
 *
 * 与课程版（Redis 会话）的差别：token 不再存储于服务端，校验是
 * "验签 + 过期 + 黑名单"三步，全部本地/一次 Redis hasKey 完成，不查用户表。
 * 无效/过期/拉黑都不在这里 401，而是不填 ThreadLocal，交给
 * LoginInterceptor 统一返回 401——两层拦截器职责分离。
 */
@Component
public class RefreshTokenInterceptor implements HandlerInterceptor {

    @Resource
    private JwtUtil jwtUtil;

    @Resource
    private org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1.获取请求头中的 accessToken
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {
            return true; // 游标放行，LoginInterceptor 判空拦截
        }
        // 2.验签 + 过期校验（无状态）
        Map<String, Object> payload = jwtUtil.parse(token);
        if (payload == null) {
            return true; // 无效 → UserHolder 为空 → 401
        }
        // 3.黑名单校验（登出/撤销的 token）
        String jti = jwtUtil.getJti(payload);
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(LOGIN_JWT_DENY_KEY + jti))) {
            return true;
        }
        // 4.从 claims 还原用户到 ThreadLocal（无状态：不查库）
        UserDTO userDTO = new UserDTO();
        userDTO.setId(Long.valueOf(String.valueOf(payload.get("uid"))));
        userDTO.setNickName(String.valueOf(payload.get("nick")));
        userDTO.setIcon(String.valueOf(payload.getOrDefault("icon", "")));
        UserHolder.saveUser(userDTO);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
