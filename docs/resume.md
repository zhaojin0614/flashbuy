# FlashBuy 简历话术定稿

> 本文是《闪购改造记录》53 章的收束：**所有数字均来自实测**（压测/explain/CI 日志可复现，复现入口见第五节）。简历上只写能在面试中接住追问的内容——每条话术对应的深度材料都在改造记录里。

## 一、项目一句话

**FlashBuy 限量抢购平台**：基于 Spring Boot 3.5 / JDK 21 / MySQL 8 / Redis 7 / RocketMQ 5.3 / Elasticsearch-ready 的高并发限量抢购与本地生活服务平台，覆盖"Redis+Lua 原子预扣 → RocketMQ 事务消息异步下单 → 消费幂等落库 → 状态机延迟关单"完整链路，配两级缓存、JWT 双 Token 认证、分布式多实例实证、CI/监控体系。

## 二、简历条目（可直接粘贴，数字可复现）

- 设计「Redis+Lua 原子预扣库存 → RocketMQ 事务消息异步下单 → 消费幂等落库」三级链路解决超卖与数据库洪峰：Lua 单脚本完成资格判断/防重/扣减，事务消息（半消息→本地事务→回查，以资格 Set 为事务证据）保障「预扣成功 ⟺ 消息必达」，消费端 SETNX/count/唯一索引三层幂等；同机双组压测**消费吞吐翻倍（125→250 单/s）、DB 并发与入口洪峰解耦、全程零超卖**；
- 引入 RocketMQ 延迟消息实现订单 15 分钟未支付自动关单（broker 定制延迟档），以状态机 CAS（`status 1→4`）保证幂等并回补库存与资格；通过真实故障迭代修复「Redis Stream PEL 滞留丢单」「锁失败静默丢单」两个分布式缺口；
- 修正“取消后可复购”的数据库口径：用可空生成列 `active_flag` 把唯一约束从全部订单收窄到有效订单（1/2/3/5），历史取消订单保留；`createOrder`、`orderState`、唯一索引三层口径统一，唯一键冲突按幂等 ACK；
- 构建 Caffeine+Redis 两级缓存（Pub/Sub 跨实例失效 + 延迟双删），并完成慢 SQL 治理：实测深分页「加索引优化器仍拒绝使用、FORCE INDEX 反而恶化 306ms」，改写**游标分页+覆盖索引**获得 **19 倍提升（194ms→10ms）**；LIKE 全表扫 → FULLTEXT ngram **4 倍（42ms→10ms）**；
- 实现 JWT 双 Token 认证（无状态 accessToken + 一次性轮换 refreshToken + jti 黑名单）、滑动窗口/令牌桶双层限流（Redis+Lua 原子脚本）、前端单飞刷新，e2e 11 项全绿；
- 工程化实证：**双实例 + Nginx 负载均衡**下同用户并发双发恰落一单（集群消费交叉分摊实证）；GitHub Actions CI 全绿（含真实 RocketMQ 集成测试）；Prometheus+Grafana 监控面板；JDK 21 虚拟线程实测边界（I/O 型接口 P99 193→36ms，MQ 串行瓶颈场景无效）。

## 三、核心数字一览（全部实测）

| 维度 | 数字 | 出处 |
|---|---|---|
| 消费吞吐（Stream→MQ 对照） | 125 → 250 单/s | `docs/seckill-loadtest.md` |
| 500 单清空 | 4s → 2s | 同上 |
| 入口 P99（可靠性代价） | 201ms → 853ms | 同上（如实写代价） |
| 两级缓存 | QPS +41%，每请求 SQL 1→0 | `docs/cache-loadtest.md` |
| 深分页治理 | 194ms → 10ms（19 倍） | `docs/slow-sql.md` |
| LIKE → FULLTEXT | 42ms → 10ms（计数一致） | 同上 |
| 虚拟线程 I/O 型 P99 | 193ms → 36ms | `docs/virtual-threads.md` |
| 双实例正确性 | 同用户并发双发恰落一单 | 改造记录第 42 章 |
| 防超卖 | 全程零超卖（三层一致性核对） | 各压测报告 |
| CI | 集成测试全绿（真实 MQ） | `.github/workflows/ci.yml` |

## 四、分模块话术（一句话 + 高频追问）

### 秒杀链路（核心卖点）
- **一句话**：Lua 三合一原子预扣（资格/防重/扣减）+ 事务消息异步落库 + 延迟关单，一人一单三道防线（Lua Set/Redisson 锁/DB count+唯一索引）。
- **高频追问**：为什么用事务消息不用本地消息表（本地事务是 Redis Lua，事务消息的本地事务可任意且资格 Set 天然是回查证据）；重复消息怎么处理（三层幂等）；为什么不需要顺序消息（同用户被锁串行化）；锁与事务的顺序（锁包住代理调用，返回即提交）。

### 缓存
- **一句话**：Caffeine+Redis 两级缓存 + Pub/Sub 跨实例失效 + 延迟双删，Cache-Aside 最终一致。
- **高频追问**：先更 DB 还是先删缓存；延迟双删的延迟怎么定；热点 Key 怎么办；大 Key 排查（`redis-cli --bigkeys`）。

### 认证与限流
- **一句话**：JWT 双 Token（轮换+黑名单）+ ZSet 滑动窗口/令牌桶双层限流（Lua 原子）+ 声明式校验。
- **高频追问**：为什么 access 无状态 refresh 有状态；登出怎么"删除"JWT（jti 黑名单，TTL=剩余有效期）；滑动窗口 vs 固定窗口 vs 令牌桶（边界突刺/突发语义）；全量强制下线（版本号方案，未做）。

### 分布式与工程化
- **一句话**：双实例+Nginx 分布式实证（无状态应用+外置状态，业务代码零改动多实例天然正确）；CI 含真实 MQ 集成测试；Grafana 面板。
- **高频追问**：多实例下你的代码哪里会出问题（答：不会——外置状态架构，有实证）；消费组分摊怎么证明（双实例日志交叉对称）；CI 里怎么跑 MQ 集成测试（services 容器+镜像直拉）。

## 五、已知边界与取舍（诚实陈述，面试加分）

- 秒杀入口吞吐受限于 MQ producer 同步发送（单 channel 串行化）——演进：事务消息异步化/批量发送；
- 虚拟线程对 MQ 串行瓶颈无效——线程收益的前提是瓶颈在线程数；
- 深分页游标分页未落地到热榜接口（治理方案已验证，接口改造待数据量需要时）；
- token 存 sessionStorage 有 XSS 风险（生产应 HttpOnly Cookie）；限流仅手机号维度；全量强制下线未做；
- ES 暂缓：LIKE 42ms/FULLTEXT 10ms 的量级数据支撑"当前不需要"，量级增长后再上。

## 六、复现入口

| 材料 | 位置 |
|---|---|
| 秒杀压测 | `docs/seckill-loadtest.md` + `scripts/loadtest_seckill.py` |
| 缓存压测 | `docs/cache-loadtest.md` |
| 慢 SQL 治理 | `docs/slow-sql.md` |
| 虚拟线程对照 | `docs/virtual-threads.md` |
| 认证时序 | `docs/auth-sequence.md` |
| 监控环境 | `docker/monitoring/`（Grafana 截图入 README） |
| 逐章原理记录 | 飞书《闪购改造记录（边改造边学习）》53 章 |
| 逐章面试问答 | 每章"面试官可能会怎么问"+《改造知识总结》 |
