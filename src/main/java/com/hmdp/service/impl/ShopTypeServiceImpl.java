package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.hmdp.utils.RedisConstants.SHOP_TYPE;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    private StringRedisTemplate stringRedisTemplate;

    public ShopTypeServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public Result queryTypeList() {

        //查询redis
        String listStr = stringRedisTemplate.opsForValue().get(SHOP_TYPE);
        //判断是否存在
        if (listStr != null) {
            List<ShopType> shopTypes = JSONUtil.toList(listStr, ShopType.class);
            return Result.ok(shopTypes);
        }
        //查询数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();
        //判断是否存在
        if (typeList == null) {
            //不存在，返回错误信息
            return Result.fail("商铺类型不存在!");
        }
        //存入redis
        stringRedisTemplate.opsForValue().set(SHOP_TYPE, JSONUtil.toJsonStr(typeList));
        //返回商铺类型
        return Result.ok(typeList);
    }
}
