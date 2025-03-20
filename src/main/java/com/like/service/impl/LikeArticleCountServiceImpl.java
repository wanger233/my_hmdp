package com.like.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.like.entity.LikeArticleCount;
import com.like.mapper.LikeArticleCountMapper;
import com.like.service.ILikeArticleCountService;
import com.like.service.ILikeBehaviorService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static com.like.utils.RedisConstants.ARTICLE_LIKE_COUNT;

@Service
public class LikeArticleCountServiceImpl extends ServiceImpl<LikeArticleCountMapper, LikeArticleCount> implements ILikeArticleCountService {


    @Resource
    private ILikeBehaviorService likeBehaviorService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    /**
     * 根据多个文章ID批量查询点赞数
     * @param articleIds
     * @return
     */
    public Map<Long, Integer> queryBatchCount(List<Long> articleIds){
        HashMap<Long, Integer> map = new HashMap<>();

        for (Long articleId : articleIds) {
            String cache = likeBehaviorService.getCache(ARTICLE_LIKE_COUNT + articleId);
            if (cache != null) {
                map.put(articleId, Integer.valueOf(cache));
            }else{
                //本地缓存没有，则查询redis
                String cache2 = stringRedisTemplate.opsForValue().get(ARTICLE_LIKE_COUNT + articleId);
                if (cache2 != null) {
                    map.put(articleId, Integer.valueOf(cache2));
                } else {
                    //本地缓存和redis都没有，则查询数据库
                    LikeArticleCount count = this.getOne(new LambdaQueryWrapper<LikeArticleCount>()
                            .eq(LikeArticleCount::getArticleId, articleId));
                    if (count != null) {
                        map.put(articleId, count.getLikeCount());
                    } else {
                        //如果数据库中也没有，则返回0
                        map.put(articleId, 0);
                    }
                }
            }
        }
        return map;
    }

    @Async
    @Override
    public void updateBatchCount(List<LikeArticleCount> countList) {
         //更新总的获赞数
        for (LikeArticleCount likeArticleCount : countList) {
            LikeArticleCount count = this.getOne(new QueryWrapper<LikeArticleCount>()
                    .eq("article_id", likeArticleCount.getArticleId()));
            if (count != null) {
                // 如果数据库中存在数据，则累加获赞总数
                count.setLikeCount(count.getLikeCount() + likeArticleCount.getLikeCount());
                this.updateById(count);
            } else {
                // 如果数据库中不存在数据，则直接保存
                this.save(likeArticleCount);
            }
        }
    }
}