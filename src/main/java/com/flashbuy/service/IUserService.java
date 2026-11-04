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

    Result sign();

    Result signCount();
}
