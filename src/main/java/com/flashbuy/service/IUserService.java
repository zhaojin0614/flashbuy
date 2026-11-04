package com.flashbuy.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.flashbuy.dto.LoginFormDTO;
import com.flashbuy.dto.Result;
import com.flashbuy.entity.User;

import jakarta.servlet.http.HttpSession;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {

    Result sendCode(String phone, HttpSession session);

    Result login(LoginFormDTO loginForm, HttpSession session);

    /**
     * 刷新登录：校验 refreshToken 并轮换，签发新的双 Token
     */
    Result refreshLogin(String refreshToken);

    Result sign();

    Result signCount();

    /**
     * 本月累计签到天数（BITCOUNT 统计整月 BitMap 中 1 的个数）
     */
    Result signMonthCount();
}
