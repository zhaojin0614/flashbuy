package com.zhaojin.flashbuy.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.zhaojin.flashbuy.dto.LoginFormDTO;
import com.zhaojin.flashbuy.dto.Result;
import com.zhaojin.flashbuy.dto.UserProfileDTO;
import com.zhaojin.flashbuy.entity.User;

import jakarta.servlet.http.HttpSession;

/**
 * <p>
 *  服务类
 * </p>
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

    Result updateProfile(UserProfileDTO dto);
}
