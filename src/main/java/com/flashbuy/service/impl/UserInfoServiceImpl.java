package com.flashbuy.service.impl;

import com.flashbuy.entity.UserInfo;
import com.flashbuy.mapper.UserInfoMapper;
import com.flashbuy.service.IUserInfoService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-24
 */
@Service
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements IUserInfoService {

}
