package com.flashbuy.utils;

import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/**
 * JWT 工具：accessToken 的签发与校验（HS256，hutool.jwt 实现，零额外依赖）
 *
 * accessToken 是无状态的：拦截器只验签名 + 过期 + 黑名单，不查任何存储。
 * 因此登出/撤销不能"删除"token（它根本不在服务端存），只能把 jti 拉黑到
 * token 自然过期为止——这是无状态认证的固有代价，见改造记录第 12 节。
 */
@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.access-ttl-hours}")
    private long accessTtlHours;

    /**
     * 签发 accessToken：claims 携带 uid/nick/icon（拦截器据此还原 UserDTO），jti 用于黑名单撤销
     */
    public String createAccessToken(Long uid, String nick, String icon) {
        return JWT.create()
                .setPayload("uid", uid)
                .setPayload("nick", nick)
                .setPayload("icon", icon == null ? "" : icon)
                .setPayload("jti", UUID.randomUUID().toString())
                .setExpiresAt(new Date(System.currentTimeMillis() + accessTtlHours * 3600_000L))
                .setKey(secret.getBytes(StandardCharsets.UTF_8))
                .sign();
    }

    /**
     * 校验并解析：签名不合法或已过期返回 null；合法返回 payload
     */
    public Map<String, Object> parse(String token) {
        try {
            JWT jwt = JWTUtil.parseToken(token).setKey(secret.getBytes(StandardCharsets.UTF_8));
            boolean ok = jwt.verify() && jwt.validate(0);
            return ok ? jwt.getPayloads() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 从已解析的 payload 中取 jti（黑名单键） */
    public String getJti(Map<String, Object> payload) {
        return String.valueOf(payload.get("jti"));
    }

    /** token 剩余有效毫秒数（黑名单 TTL 用，保证黑名单在 token 过期后自清理） */
    public long remainingMillis(Map<String, Object> payload) {
        // JWT 标准的 exp claim 是秒级时间戳，要 ×1000 对齐毫秒
        long exp = Long.parseLong(String.valueOf(payload.get("exp")));
        return Math.max(0, exp * 1000 - System.currentTimeMillis());
    }
}
