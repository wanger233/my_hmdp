package com.like.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.hmdp.dto.Result;
import com.hmdp.entity.Event;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import com.like.dto.LikeBehaviorDTO;
import com.like.entity.LikeBehavior;
import com.like.event.KafkaLikeProducer;
import com.like.mapper.LikeBehaviorMapper;
import com.like.service.IArticleService;
import com.like.service.ILikeBehaviorService;
import com.like.utils.BloomFilterService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.HashMap;
import java.util.List;
import java.util.Set;

import static com.like.utils.KafkaConstants.TOPIC_LIKE_BEHAVIOR;
import static com.like.utils.RedisConstants.*;

@Service
@Slf4j
public class LikeBehaviorServiceImpl extends ServiceImpl<LikeBehaviorMapper, LikeBehavior> implements ILikeBehaviorService {


    @Resource
    private BloomFilterService bloomFilterService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private KafkaLikeProducer kafkaLikeProducer;
    @Resource
    private LoadingCache<String, String> cache;
    @Resource
    private IArticleService articleService;
    @Resource
    private IUserService userService;

    /**
     * 判断用户是否点赞
     * @param
     * @return
     */
    private boolean isLike(Long articleId, Long userId) {
        //先判断布隆过滤器是否有点赞记录
        boolean isExist = bloomFilterService.isExist(LIKE_BEHAVIOR_BLOOM_FILTER,
                articleId.toString(),
                userId.toString());
        //没有，说明没点赞，直接返回
        if (!isExist) {
            return false;
        }
        //查看redis的ZSet有没有点赞记录
        if (stringRedisTemplate.opsForZSet()
                .score(USER_LIKE_ZSET_KEY+userId, articleId.toString()) != null
        || stringRedisTemplate.opsForZSet()
                .score(ARTICLE_LIKE_ZSET_KEY+articleId, userId.toString()) != null) {
            //有，说明点赞了，直接返回
            return true;
        }
        //查询MySQL是否有点赞记录
        LikeBehavior likeBehavior = this.getOne(
                new QueryWrapper<LikeBehavior>()
                        .eq("article_id", articleId)
                        .eq("user_id", userId)
                        .orderByDesc("create_time")
                        .last("limit 1")
        );
        //没有，说明没点赞，直接返回
        if (likeBehavior == null || likeBehavior.getType() == 0) {
            return false;
        }
        //有，说明点赞了，直接返回
        return true;
    }

    @Override
    public Result like(LikeBehaviorDTO likeBehaviorDTO) {

        Long articleId = likeBehaviorDTO.getArticleId();
        Long userId = UserHolder.getUser().getId();
        //判断行为是点赞还是取消点赞
        Integer type = likeBehaviorDTO.getType();
        if (type == 1){
            //点赞
            //判断是否已经点赞
            if (isLike(articleId, userId)) {
                //已经点赞，直接返回
                //是，返回错误
                return Result.fail("已经点赞,请勿重复点赞！");
            }
            //否，继续
            //将点赞行为存入布隆过滤器
            bloomFilterService.addKeyToBloomFilter(LIKE_BEHAVIOR_BLOOM_FILTER,
                    articleId.toString(),
                    userId.toString());
        }else{
            //取消点赞
            if (!isLike(articleId,userId)){
                //当前没有点赞，返回错误
                return Result.fail("你还没有点赞,请勿重复取消点赞！");
            }
        }
        //将行为传给kafka
        //先给行为生成一个id
        long behaviorId = redisIdWorker.nextId(LIKE_BEHAVIOR);
        sendLikeBehaviorMsg(behaviorId, articleId, userId, type);

        return Result.ok();
    }

    /**
     * 保存点赞行为日志，保存到db和redis
     * @param likeBehavior
     * @return
     */
    @Override
    public boolean saveLikeLog(LikeBehavior likeBehavior) {
        //保存到数据库
        if (!this.save(likeBehavior)) {
            return false;
        }
        //更新redis
        updateRedis(likeBehavior);
        //更新到本地缓存
        updateLocalCache(likeBehavior);
        return true;
    }

    @Override
    public String getCache(String key) {
        return cache.get(key);
    }

    @Async
    public void updateLocalCache(LikeBehavior likeBehavior) {
        //先解析出数据
        Long userId = likeBehavior.getUserId();
        Long articleId = likeBehavior.getArticleId();
        Integer type = likeBehavior.getType();
        int diff = type == 1 ? 1 : -1;
        Integer articleCount = Integer.valueOf(cache.get(ARTICLE_LIKE_COUNT + articleId));
        Integer userCount = Integer.valueOf(cache.get(USER_LIKE_COUNT + userId));
        //只有缓存中已经有数据才会更新，如果本来就没有数据的则不会存入local cache
        if (articleCount != null){
            cache.put(ARTICLE_LIKE_COUNT + articleId,String.valueOf(articleCount + diff));
        }
        if (userCount != null) {
            cache.put(USER_LIKE_COUNT + userId, String.valueOf(userCount + diff));
        }
    }

    private void updateRedis(LikeBehavior likeBehavior) {
        //先解析出数据
        Long userId = likeBehavior.getUserId();
        Long articleId = likeBehavior.getArticleId();
        Integer type = likeBehavior.getType();
        //更新redis
        updateRedisZset(userId, articleId, type);
        //文章点赞总数和用户点赞总数的更新
        //更新文章点赞总数
        String key = ARTICLE_LIKE_COUNT + articleId;
        if (updateRedisLikeCount(key, type) == -1){
            log.info("文章获赞总数数据异常，准备删除异常的redis数据...");
            stringRedisTemplate.delete(ARTICLE_LIKE_COUNT + articleId);
            log.info("异常数据删除成功！");
        }
        //更新用户点赞总数
        key = USER_LIKE_COUNT + userId;
        if (updateRedisLikeCount(key, type) == -1){
            log.info("用户获赞总数数据异常，准备删除异常的redis数据...");
            stringRedisTemplate.delete(USER_LIKE_COUNT + userId);
            log.info("异常数据删除成功！");
        }
    }

    private int updateRedisLikeCount(String key, Integer type) {
        int diff = type == 1 ? 1 : -1;
        String count = stringRedisTemplate.opsForValue().get(key);
        //如果没有，说明是第一次点赞
        if (count == null){
            if (diff == 1){
                stringRedisTemplate.opsForValue().set(key, "1");
            }
        }else{
            if (Integer.parseInt(count) + diff < 0){
                //如果小于0，说明取消点赞的数量大于点赞的数量 返回异常-1
                return -1;
            }else{
                //否则，更新数量
                stringRedisTemplate.opsForValue().set(key, String.valueOf(Integer.parseInt(count) + diff));
            }
        }
        return count == null ? 1 : Integer.parseInt(count) + diff;//返回自然数表示正常
    }

    private void updateRedisZset(Long userId, Long articleId, Integer type) {
        //判断是点赞还是取消赞
        String userKey = USER_LIKE_ZSET_KEY + userId;
        String articleKey = ARTICLE_LIKE_ZSET_KEY + articleId;
        if (type == 1) {
            //点赞
            //将用户点赞的文章id存入redis
            long score = System.currentTimeMillis();
            Boolean isSuccess = stringRedisTemplate.opsForZSet()
                    .add(userKey, articleId.toString(), score);
            if (isSuccess == null || !isSuccess) {
                //如果添加失败，则删除
                stringRedisTemplate.delete(userKey);
            }
            //对于长度超过200的zset，只保留前200个点赞的文章id
            trimLikeZsetList(userKey);
            //将文章点赞的用户id存入redis
            Boolean isSuccess2 = stringRedisTemplate.opsForZSet()
                    .add(articleKey, userId.toString(), score);
            if (isSuccess2 == null || !isSuccess2) {
                //如果添加失败，则删除
                stringRedisTemplate.delete(articleKey);
            }
            //对于长度超过200的zset，只保留前200个点赞的用户id
            trimLikeZsetList(articleKey);
        } else {
            //取消点赞
            //将用户点赞的文章id从redis中删除
            stringRedisTemplate.opsForZSet()
                    .remove(userKey, articleId.toString());
            //将文章点赞的用户id从redis中删除
            stringRedisTemplate.opsForZSet()
                    .remove(articleKey, userId.toString());
        }
    }

    @Async
    public void trimLikeZsetList(String key) {
        Long count = stringRedisTemplate.opsForZSet().zCard(key);
        if (count != null && count > ZSET_LENGTH_LIMIT) {
            //如果长度超过200,则删除前面的数据
            int needCount = (int) (count - ZSET_LENGTH_LIMIT);
            Set<String> strings = stringRedisTemplate.opsForZSet()
                    .reverseRangeByScore(key, 0, needCount - 1);//从小到大排序
            if (strings != null && strings.size() > 0) {
                //删除前面的数据 时间戳小的删了
                stringRedisTemplate.opsForZSet()
                        .remove(key, strings.toArray());
            }
        }
    }

    private void sendLikeBehaviorMsg(long behaviorId, Long articleId, Long userId, Integer type) {

        HashMap<String, Object> data = new HashMap<>();
        data.put("articleId", articleId);
        data.put("type", type);
        Event event = new Event();
        event.setUserId(userId);
        event.setTopic(TOPIC_LIKE_BEHAVIOR);
        event.setEntityId(articleId);
        event.setData(data);
        kafkaLikeProducer.publishLikeEvent(event);

    }

    /**
     * 拉取热点文章到本地缓存
     * @return
     */
    @Scheduled(fixedRate = 2 * 60 * 60 * 1000)// 每2小时执行一次
    public void pullHotArticleList() {
        //先查询热点文章
        List<Long> hotArticles = articleService.queryHotArticle();
        //热点文章是否在redis中
        for (Long hotArticle : hotArticles) {
            String count = stringRedisTemplate.opsForValue()
                    .get(ARTICLE_LIKE_COUNT + hotArticle);
            if (count != null) {
                cache.put(ARTICLE_LIKE_COUNT + hotArticle, count);
            }
        }
    }

    /**
     * 拉取热点用户到本地缓存
     * @return
     */
    @Scheduled(fixedRate = 2 * 60 * 60 * 1000)// 每2小时执行一次
    public void pullHotUserList() {
        List<Long> hotUsers = userService.queryHotUser();
        for (Long hotUser : hotUsers) {
            String count = stringRedisTemplate.opsForValue()
                    .get(USER_LIKE_COUNT + hotUser);
            if (count != null) {
                cache.put(USER_LIKE_COUNT + hotUser, count);
            }
        }
    }



}
