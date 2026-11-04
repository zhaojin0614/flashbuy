package com.flashbuy.config;

import com.flashbuy.interceptor.LoginInterceptor;
import com.flashbuy.interceptor.RefreshTokenInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Slf4j
@Configuration
public class MvcConfig implements WebMvcConfigurer {
    @Autowired
    private LoginInterceptor loginInterceptor;
    @Autowired
    private RefreshTokenInterceptor refreshTokenInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        log.info("开始注册自定义拦截器...");
        //登录拦截器
        registry.addInterceptor(loginInterceptor)
                .excludePathPatterns("/user/code")
                .excludePathPatterns("/user/login")
                .excludePathPatterns("/post/hot")
                .excludePathPatterns("/merchant/**")
                .excludePathPatterns("/merchant-type/**")
                .excludePathPatterns("/upload/**")
                .excludePathPatterns("/product/**").order(1);
        //token刷新拦截器
        registry.addInterceptor(refreshTokenInterceptor).order(0);

    }
}
