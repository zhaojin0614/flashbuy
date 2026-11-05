package com.flashbuy.interceptor;

import com.flashbuy.utils.UserHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 登录强制校验拦截器：只判断当前请求是否已经由 JwtAuthInterceptor 还原出用户。
 *
 * JWT 的解析与用户上下文填充在 JwtAuthInterceptor 完成；本拦截器只负责
 * 受保护接口的访问控制——UserHolder 为空时返回 401。
 */
@Component
public class LoginRequiredInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (UserHolder.getUser() == null) {
            response.setStatus(401);
            return false;
        }
        return true;
    }
}
