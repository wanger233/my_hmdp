package com.like.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.like.entity.Article;
import com.like.entity.LikeArticleCount;
import com.like.entity.LikeUserCount;
import com.like.mapper.ArticleMapper;
import com.like.mapper.LikeUserCountMapper;
import com.like.service.ILikeBehaviorService;
import com.like.service.ILikeUserCountService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.like.utils.RedisConstants.ARTICLE_LIKE_COUNT;
import static com.like.utils.RedisConstants.USER_LIKE_COUNT;

@Service
public class LikeUserCountServiceImpl extends ServiceImpl<LikeUserCountMapper, LikeUserCount> implements ILikeUserCountService {

    @Resource
    private ArticleMapper articleMapper;
    @Resource
    private ILikeBehaviorService likeBehaviorService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public Map<Long, Integer> queryBatchCount(List<Long> userIds) {
        HashMap<Long, Integer> map = new HashMap<>();

        for (Long userId : userIds) {
            String cache = likeBehaviorService.getCache(USER_LIKE_COUNT + userId);
            if (cache != null) {
                map.put(userId, Integer.valueOf(cache));
            }else{
                //本地缓存没有，则查询redis
                String cache2 = stringRedisTemplate.opsForValue().get(USER_LIKE_COUNT + userId);
                if (cache2 != null) {
                    map.put(userId, Integer.valueOf(cache2));
                } else {
                    //本地缓存和redis都没有，则查询数据库
                    LikeUserCount count = this.getOne(new LambdaQueryWrapper<LikeUserCount>()
                            .eq(LikeUserCount::getUserId, userId));
                    if (count != null) {
                        map.put(userId, count.getLikeCount());
                    } else {
                        //如果数据库中也没有，则返回0
                        map.put(userId, 0);
                    }
                }
            }
        }
        return map;
    }

    @Override
    public void updateBatchCount(List<LikeArticleCount> countList) {
        //更新用户的总获赞数
        for (LikeArticleCount likeArticleCount : countList) {
            //根据articleId查询对应的authorId
            Long articleId = likeArticleCount.getArticleId();
            Article article = articleMapper.selectById(articleId);
            Integer likeCount = likeArticleCount.getLikeCount();
            if (article != null){
                Long authorId = article.getUserId();
                LikeUserCount userCount = this.getOne(new LambdaQueryWrapper<LikeUserCount>()
                        .eq(LikeUserCount::getUserId, authorId));
                if (userCount != null) {
                    userCount.setLikeCount(userCount.getLikeCount() + likeCount);
                    this.updateById(userCount);
                }else{
                    userCount = new LikeUserCount();
                    userCount.setUserId(authorId);
                    userCount.setLikeCount(likeCount);
                    this.save(userCount);
                }
            }
        }
    }
}