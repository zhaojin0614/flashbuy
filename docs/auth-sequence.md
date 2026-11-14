# 认证时序图（P1 补充交付物）

覆盖闪购认证体系的四条主链路：**登录签发 → 请求认证 → 过期刷新 → 登出撤销**。
图中键名、TTL、状态码与代码逐项对应，代码锚点以 `类#方法` 标注。

涉及代码：

| 角色 | 位置 |
|---|---|
| 签发/刷新/登出 | `UserController`、`UserServiceImpl` |
| JWT 工具 | `utils/JwtUtil` |
| 拦截器链 | `interceptor/JwtAuthInterceptor`（order 0）、`interceptor/LoginRequiredInterceptor`（order 1） |
| 前端刷新 | `nginx-1.18.0/html/flashbuy/js/common.js` |

---

## 1. 登录：验证码 + 双 Token 签发

```
前端                        后端                             Redis
 │                           │                                │
 │ ① POST /user/code?phone   │                                │
 ├──────────────────────────►│ 校验手机号格式                   │
 │                           ├─ 滑动窗口限流 rl:code:{phone} ─►│ ZREMRANGEBYSCORE→ZCARD→ZADD
 │                           │  （60s 内最多 1 次，防短信轰炸）  │
 │                           ├─ 生成 6 位验证码 ──────────────►│ SET login:code:{phone} EX 120s
 │ ◄──────── success ────────┤（验证码打在控制台日志，演示环境） │
 │                           │                                │
 │ ② POST /user/login        │                                │
 │   {phone, code}           │                                │
 ├──────────────────────────►│ 校验手机号格式                   │
 │                           ├─ 滑动窗口限流 rl:login:{phone} ─►│ （60s 内最多 5 次，防暴力猜码）
 │                           ├─ GET login:code:{phone} ───────►│ 比对验证码（不匹配→fail）
 │                           ├─ 查 tb_user；不存在则注册        │
 │                           │   （昵称 user_+随机串）          │
 │                           │                                │
 │                           │ 签发双 Token：                   │
 │                           │  accessToken = JWT HS256        │
 │                           │    claims: uid/nick/icon/jti    │
 │                           │    exp = 2h（access-ttl-hours） │
 │                           │  refreshToken = 随机 UUID        │
 │                           ├───────────────────────────────►│ SET login:refresh:{uuid} = uid
 │                           │                                │   EX 168h（7 天）
 │ ◄─ {accessToken,          │                                │
 │     refreshToken} ────────┤                                │
 │  sessionStorage 存储       │                                │
```

要点：accessToken **无状态**（后续校验不查库不查会话）；refreshToken **有状态**（Redis 可撤销、可轮换）。高频的 access 追求性能，低频的 refresh 追求可控。

---

## 2. 请求认证：拦截器链（认证与鉴权分离）

```
前端                        JwtAuthInterceptor(0)              LoginRequiredInterceptor(1)      目标 Controller
 │                           │                                   │                               │
 ├─ authorization: token ───►│                                   │                               │
 │                           │ parse(token)：验签 + exp 过期校验  │                               │
 │                           │  ├─ 无效 ──► 放行（不填 UserHolder）──►│ UserHolder == null？          │              │
 │                           │  │                                 │  ├─ 是 && 非白名单 ──► 401     │              │
 │                           │  │                                 │  └─ 是 && 白名单 ────► 放行    │              │
 │                           │  └─ 有效 ─► 黑名单校验              │                               │              │
 │                           │        GET login:jwt:deny:{jti}   │                               │              │
 │                           │         ├─ 命中（已登出）─► 放行（不填 UserHolder）│                │              │
 │                           │         └─ 未命中 ─► claims 还原 UserDTO           │               │              │
 │                           │                     UserHolder.saveUser ──────────┼──────────────►│ 业务处理      │
 │                           │                                   │                               │              │
 │                           │ afterCompletion：UserHolder.removeUser（Tomcat 线程复用，防串号）    │              │
```

要点：
- 认证拦截器（order 0）注册在**所有路径**上，公开接口也会经过并可选识别用户（如登录后显示点赞状态）；白名单在 `MvcConfig`：`/user/code`、`/user/login`、`/user/refresh`、`/post/hot`、`/post/latest`、`/post/of/merchant`、`/merchant/**`、`/merchant-type/**`、`/upload/**`、`/product/**`。
- 无效 token 在第一个拦截器**只放行不 401**，由第二个拦截器统一做访问控制——职责分离：JwtAuthInterceptor 管"你是谁"，LoginRequiredInterceptor 管"你能不能访问"。
- `afterCompletion` 清理 ThreadLocal 必须留在第一个拦截器：它覆盖所有路径；若挂在第二个拦截器上，公开接口不会清理，Tomcat 线程复用会把上一个请求的用户串到下一个请求。

---

## 3. 过期刷新：单飞 + 一次性轮换 + 重放

```
前端（common.js axios 响应拦截）            后端                            Redis
 │                                          │                               │
 │ 业务请求收到 401                          │                               │
 ├─ 并发多个 401 只放行一个刷新（单飞）        │                               │
 │   共享 refreshingPromise                  │                               │
 │ ① POST /user/refresh                     │                               │
 │   {refreshToken}                         │                               │
 ├─────────────────────────────────────────►│ GET login:refresh:{rt} ──────►│ 取 uid
 │                                          │  ├─ 不存在 ──► fail"登录已过期" │
 │                                          │  └─ 存在 ──► 查 tb_user        │
 │                                          │     一次性轮换（防重放）：        │
 │                                          ├─ DEL login:refresh:{旧rt} ───►│
 │                                          ├──────────────────────────────►│ SET login:refresh:{新rt}=uid
 │                                          │   签发新 accessToken(2h)        │  EX 168h
 │ ◄─ 新 {accessToken, refreshToken} ───────┤                                │
 │                                          │                               │
 │ ② sessionStorage 更新                     │                               │
 │ ③ 各请求带 _retried 标记重放原请求          │（_retried 防刷新后仍 401 造成二次循环）│
 │ ④ 刷新失败：清 token 跳登录页（登录页除外， │                               │
 │    避免登录过程中的 401 造成死循环）        │                               │
```

要点：**轮换的价值**——被盗的旧 refreshToken 在受害者下次刷新时必然失效（旧 token 已被用掉），盗用可被感知；**单飞的价值**——页面并发 N 个请求同时 401 时只发一次 /user/refresh，其余共享同一个 Promise 等待，避免 N 次轮换把彼此的 refreshToken 打失效。每次刷新重置 7 天 TTL（滑动会话），持续使用可一直续命。

---

## 4. 登出：jti 黑名单 + refreshToken 删除

```
前端                        后端                                  Redis
 │                           │                                     │
 │ ① POST /user/logout       │                                     │
 │   authorization: token    │                                     │
 │   body: {refreshToken}    │                                     │
 ├──────────────────────────►│ parse(token)：验签+过期校验           │
 │                           │  ├─ token 无效 ──► 跳过黑名单         │
 │                           │  └─ 有效 ─► 计算 TTL = remainingMillis│
 │                           │     （exp 秒级 ×1000 对齐毫秒）        │
 │                           ├─ SET login:jwt:deny:{jti}="1" ─────►│ TTL = token 剩余有效期
 │                           ├─ DEL login:refresh:{rt} ───────────►│
 │ ◄──────── success ────────┤                                     │
 │                           │                                     │
 │ ② 旧 accessToken 在自然过期前仍可被解析，但黑名单命中 → 不填 UserHolder → 受保护接口 401
 │ ③ 黑名单随 token 过期自动清理（TTL 到期），Redis 无残留
```

要点：JWT 无状态——服务端没有可"删除"的 token，登出只能把 jti 拉黑到自然过期，这是无状态认证的固有代价；黑名单 TTL = 剩余有效期保证"恰好覆盖到 token 失效为止"，不多留也不少留。全设备强制下线需要 token 版本号方案（claims 带版本，用户版本 +1 全部失效），当前未做。

---

## 5. 关键常量速查（与代码对应）

| 项 | 值 | 代码锚点 |
|---|---|---|
| 验证码 TTL | 2 分钟 | `RedisConstants.LOGIN_CODE_TTL` |
| 验证码限流 | 1 次/60s（按手机号） | `UserServiceImpl#sendCode` |
| 登录限流 | 5 次/60s（按手机号） | `UserServiceImpl#login` |
| accessToken 有效期 | 2 小时 | `application.yaml jwt.access-ttl-hours` |
| refreshToken 有效期 | 168 小时（7 天） | `RedisConstants.LOGIN_REFRESH_TTL` |
| refresh 轮换 | 删旧发新（一次性） | `UserServiceImpl#refreshLogin` |
| JWT 黑名单 | `login:jwt:deny:{jti}`，TTL=剩余有效期 | `UserController#logout`、`JwtAuthInterceptor` |
| 用户上下文 | ThreadLocal（`UserHolder`），afterCompletion 清理 | `JwtAuthInterceptor#afterCompletion` |
| 前端存储 | sessionStorage（token/refreshToken） | `common.js` |

## 设计权衡速览

- **为什么 access 无状态、refresh 有状态**：access 每请求校验，验签+过期+黑名单一次 Redis hasKey 即完成，不查用户表；refresh 低频，存 Redis 才能撤销与轮换。双 Token 在"性能"与"可控"之间取平衡。
- **为什么黑名单 TTL = 剩余有效期**：登出的目的只是"让这个 token 失效到它自然过期为止"——TTL 长了浪费内存，短了登出失效。
- **已知边界**：token 存 sessionStorage 有 XSS 风险（生产推荐 HttpOnly Cookie + CSRF 防护）；限流仅手机号维度（可叠加 IP/设备指纹）；全量强制下线未做（版本号方案）。详见《改造知识总结》JWT 主题。
