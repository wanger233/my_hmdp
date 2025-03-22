package com.like.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.like.dto.LikeUserDTO;
import com.like.entity.LikeBehavior;
import com.like.mapper.LikeUserMapper;
import com.like.service.ILikeUserService;
import org.checkerframework.checker.units.qual.A;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.like.utils.RedisConstants.USER_LIKE_ZSET_KEY;

@Service
public class LikeUserServiceImpl extends ServiceImpl<LikeUserMapper, LikeBehavior> implements ILikeUserService {


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result getUserLikeList(LikeUserDTO dto) {

        //存放用户点赞的文章ID
        List<Long> list;
        //先查redis
        String key = USER_LIKE_ZSET_KEY + dto.getUserId();
        Long count = stringRedisTemplate.opsForZSet().size(key);
        //等于200时，我们就默认是超过了200的
        if (count != null && count > 0 && count < 200) {
            //直接返回
            Set<String> set = stringRedisTemplate.opsForZSet().range(key, 0, -1);
            list = set.stream().map(Long::valueOf).collect(Collectors.toList());
        } else {
            //查数据库
            List<LikeBehavior> list1 = this.lambdaQuery()
                    .eq(LikeBehavior::getUserId, dto.getUserId())
                    .list();
            if (list1 == null || list1.size() == 0) {
                //缓存null到redis
                stringRedisTemplate.opsForZSet().add(key, "null", 0);
                return Result.ok(Collections.emptyList());
            }else{
                list = list1.stream().map(LikeBehavior::getArticleId).collect(Collectors.toList());
                //存入redis
                for (LikeBehavior likeBehavior : list1) {
                    //这里没有考虑如果取消点赞的情况，我们直接就返回了点赞的情况
                    //可能中间取消了点赞，然后又点上了（多次）的问题
                    long score = System.currentTimeMillis();
                    stringRedisTemplate.opsForZSet().add(key, String.valueOf(likeBehavior.getArticleId()), score);
                }
            }
        }

        return Result.ok(list);
    }
}
