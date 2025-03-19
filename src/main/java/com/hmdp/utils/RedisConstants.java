package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 36000L;
    //防刷机制：登录锁，用于防止恶意刷验证码
    public static final String LOGIN_LOCK = "login:lock:";
    public static final Long LOGIN_LOCK_TTL = 1L;
    //黑名单机制：维护一个黑名单，用于防止恶意刷验证码
    public static final String LOGIN_FAIL_PHONE = "login:fail:phone:";
    public static final Long LOGIN_FAIL_TTL = 36000L; // 两小时

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";

    public static final String SHOP_TYPE = "shopType";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String SECKILL_ORDER_KEY = "seckill:order:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";
}
