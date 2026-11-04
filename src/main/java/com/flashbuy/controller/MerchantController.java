package com.flashbuy.controller;


import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flashbuy.dto.Result;
import com.flashbuy.entity.Merchant;
import com.flashbuy.service.IMerchantService;
import com.flashbuy.utils.SystemConstants;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/merchant")
public class MerchantController {

    @Resource
    public IMerchantService merchantService;

    /**
     * 根据id查询商铺信息
     * @param id 商铺id
     * @return 商铺详情数据
     */
    @GetMapping("/{id}")
    public Result queryMerchantById(@PathVariable("id") Long id) {
        return merchantService.queryById(id);
    }

    /**
     * 新增商铺信息
     * @param merchant 商铺数据
     * @return 商铺id
     */
    @PostMapping
    public Result saveMerchant(@RequestBody Merchant merchant) {
        // 写入数据库
        merchantService.save(merchant);
        // 返回店铺id
        return Result.ok(merchant.getId());
    }

    /**
     * 更新商铺信息
     * @param merchant 商铺数据
     * @return 无
     */
    @PutMapping
    public Result updateMerchant(@RequestBody Merchant merchant) {
        // 写入数据库
        return merchantService.update(merchant);
    }

    /**
     * 根据商铺类型分页查询商铺信息
     * @param typeId 商铺类型
     * @param current 页码
     * @return 商铺列表
     */
    @GetMapping("/of/type")
    public Result queryMerchantByType(
            @RequestParam("typeId") Integer typeId,
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "x", required = false) Double x,
            @RequestParam(value = "y", required = false) Double y
    ) {
        return merchantService.queryMerchantByType(typeId, current, x, y);
    }

    /**
     * 根据商铺名称关键字分页查询商铺信息
     * @param name 商铺名称关键字
     * @param current 页码
     * @return 商铺列表
     */
    @GetMapping("/of/name")
    public Result queryMerchantByName(
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "current", defaultValue = "1") Integer current
    ) {
        // 根据类型分页查询
        Page<Merchant> page = merchantService.query()
                .like(StrUtil.isNotBlank(name), "name", name)
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 返回数据
        return Result.ok(page.getRecords());
    }
}
