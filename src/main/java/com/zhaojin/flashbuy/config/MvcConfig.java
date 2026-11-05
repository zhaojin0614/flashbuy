package com.zhaojin.flashbuy.config;

import com.zhaojin.flashbuy.interceptor.JwtAuthInterceptor;
import com.zhaojin.flashbuy.interceptor.LoginRequiredInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Slf4j
@Configuration
public class MvcConfig implements WebMvcConfigurer {
    @Autowired
    private LoginRequiredInterceptor loginRequiredInterceptor;
    @Autowired
    private JwtAuthInterceptor jwtAuthInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        log.info("开始注册自定义拦截器...");
        // 登录校验拦截器
        registry.addInterceptor(loginRequiredInterceptor)
                .excludePathPatterns("/user/code")
                .excludePathPatterns("/user/login")
                .excludePathPatterns("/user/refresh")
                .excludePathPatterns("/post/hot")
                .excludePathPatterns("/post/of/merchant")
                .excludePathPatterns("/merchant/**")
                .excludePathPatterns("/merchant-type/**")
                .excludePathPatterns("/upload/**")
                .excludePathPatterns("/product/**").order(1);
        // JWT 解析拦截器
        registry.addInterceptor(jwtAuthInterceptor).order(0);

    }
}
