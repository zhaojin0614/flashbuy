package com.flashbuy.controller;


import com.flashbuy.dto.Result;
import com.flashbuy.entity.MerchantType;
import com.flashbuy.service.IMerchantTypeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import java.util.List;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/merchant-type")
public class MerchantTypeController {
    @Resource
    private IMerchantTypeService typeService;

    @GetMapping("list")
    public Result queryTypeList() {
        return typeService.queryTypeList();
    }
}
