package com.flashbuy.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 24L;
    /** refreshToken：随机串 -> uid，7 天有效，一次性轮换 */
    public static final String LOGIN_REFRESH_KEY = "login:refresh:";
    public static final Long LOGIN_REFRESH_TTL = 168L;
    /** JWT 黑名单：jti -> 1，TTL = token 剩余有效期（登出/撤销用） */
    public static final String LOGIN_JWT_DENY_KEY = "login:jwt:deny:";

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_MERCHANT_TTL = 30L;
    public static final String CACHE_MERCHANT_KEY = "cache:merchant:";
    public static final Long CACHE_MERCHANTTYPE_TTL = 30L;
    public static final String CACHE_MERCHANTTYPE_KEY = "cache:merchanttype";

    public static final String LOCK_MERCHANT_KEY = "lock:merchant:";
    public static final Long LOCK_MERCHANT_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String SECKILL_ORDER_KEY = "seckill:order:";
    public static final String POST_LIKED_KEY = "post:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String MERCHANT_GEO_KEY = "merchant:geo:";
    public static final String USER_SIGN_KEY = "sign:";
}
