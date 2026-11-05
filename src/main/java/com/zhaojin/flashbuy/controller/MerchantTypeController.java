package com.zhaojin.flashbuy.controller;


import com.zhaojin.flashbuy.dto.Result;
import com.zhaojin.flashbuy.entity.MerchantType;
import com.zhaojin.flashbuy.service.IMerchantTypeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import java.util.List;

/**
 * <p>
 * 前端控制器
 * </p>
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
