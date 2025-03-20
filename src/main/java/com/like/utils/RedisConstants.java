package com.like.utils;

public class RedisConstants {
    //用户点赞
    public static final String USER_LIKE_ZSET_KEY = "likeZset:userId:";
    //文章点赞
    public static final String ARTICLE_LIKE_ZSET_KEY = "likeZset:articleId:";
    //是否点赞
    public static final String IS_LIKE_KEY = "isLike:";
    //点赞过期时间
    public static final Long IS_LIKE_TTL = 24L;
    //文章点赞数量
    public static final String ARTICLE_LIKE_COUNT = "likeCount:articleId:";
    //用户点赞数量
    public static final String USER_LIKE_COUNT = "likeCount:userId:";
    //点赞行为存入布隆过滤器 过滤器的名称
    public static final String LIKE_BEHAVIOR_BLOOM_FILTER = "like-behavior-bloom-filter";
    //zset长度限制
    public static final Integer ZSET_LENGTH_LIMIT = 200;
    //like-behavior
    public static final String LIKE_BEHAVIOR = "like-behavior";

}
