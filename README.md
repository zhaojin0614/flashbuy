# 闪购 FlashBuy ⚡

> 潮玩 / 演出票限量抢购平台 —— 一个以「限量抢购」为核心业务的高并发实战项目。
> 源自对黑马点评课程的系统性学习，并在其之上完成了技术栈升级、架构重构与品牌重塑。

## 核心亮点

### ⚡ 秒杀 / 抢购链路（重点）

```
请求线程                          Redis                          后台消费线程
   │                                                                │
   ├── seckill.lua 三合一原子脚本 ──► 资格判断 + 预扣库存 + XADD      │
   │   （库存校验 / Set 防重 / 扣减 / 发消息一次完成）                   │
   │                                                                │
   └── 立即返回订单号                  stream.orders ──XREADGROUP──► VoucherOrderHandler
                                                                    │  Redisson 分布式锁（一人一单）
                                                                    │  proxy.createOrder() @Transactional
                                                                    │  数据库乐观锁扣库存（防超卖兜底）
                                                                    ├── XACK 确认
                                                                    └── 异常时 handlePendingList 补偿
```

- 一人一单三道防线：Lua 的 Set 判断 → Redisson 锁 → 数据库 count 校验
- 事务保障：锁包住整个事务调用，`proxy.createOrder` 返回即事务已提交后才释放锁
- 消息可靠性：消费组 + 手动 ACK + pending-list 补偿，消息不丢、幂等消费

### 🧊 缓存体系
- 缓存穿透（空值缓存）、击穿（逻辑过期 + 互斥锁）、雪崩（随机 TTL）
- 通用 `CacheClient`：函数式编程封装任意实体缓存逻辑
- 缓存一致性：先更新数据库再删除缓存

### 🔐 分布式锁演进
SETNX 简易锁 → 线程标识防误删 → Lua 保证释放原子性 → **Redisson 看门狗**

### 🏘 其他功能
GEO 附近商户 · BitMap 签到与连续统计 · HyperLogLog UV 统计 ·
推模式 Feed 流（滚动分页）· 点赞 SortedSet 与排行榜 · Redis 全局唯一 ID

## 技术栈（2026 基线）

| 层 | 技术 |
|---|---|
| 语言 / 运行时 | JDK 21 |
| 框架 | Spring Boot 3.5.16 |
| ORM | MyBatis-Plus 3.5.18.1 |
| 缓存 / MQ | Redis 7（Lettuce）、Redisson 3.50、Redis Stream 消息队列 |
| 数据库 | MySQL 8.0 |
| 前端 | 原生 JS + 手写 CSS（无框架依赖），nginx 8080 反代 |
| 版本控制 | Git（每次改造独立提交，可追溯） |

## 快速启动

```bash
# 1. 数据库：执行 src/main/resources/db/flashbuy.sql
mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS flashbuy DEFAULT CHARACTER SET utf8mb4;"
mysql -uroot -p flashbuy < src/main/resources/db/flashbuy.sql

# 2. Redis：localhost:6379（application.yaml 可改）

# 3. 启动后端（8081）
mvn spring-boot:run

# 4. 启动前端（8080，/api 反代 8081）
cd nginx-1.18.0 && ./nginx.exe

# 5. 浏览器打开 http://localhost:8080
#    登录验证码输出在应用控制台日志（演示环境）
```

## 接口概览

| 模块 | 路径 | 说明 |
|---|---|---|
| 用户 | `/user/code` `/user/login` `/user/me` `/user/sign` | 验证码、登录、当前用户、BitMap 签到 |
| 商户 | `/merchant/{id}` `/merchant/of/type` `/merchant/of/name` | 详情、分类检索、名称检索 |
| 商品 | `/product/list/{merchantId}` `/product/seckill` | 商品列表、上架限量商品 |
| 抢购 | `POST /order/seckill/{productId}` | Lua 原子预扣 + 异步落库 |
| 晒单 | `/post/hot` `/post/{id}` `/post/like/{id}` | 热门晒单、点赞 |

## 改造记录

- `ce53e04` Spring Boot 2.7 → 3.5.16 / JDK 21 / MyBatis-Plus 3.5.18（含 MP 3.5.9+ 拆包迁移）
- `d029137` 选题改名：包名 / 表名 / 类名 / 接口路径全部语义化，数据库重构为 flashbuy
- `88c4a0e` 全新前端 + Lua 消息字段名修复 + 端到端验证

## Roadmap

- [ ] P1 认证与安全：JWT 双 Token + 登录限流（Redis + Lua 滑动窗口）
- [ ] P3 秒杀增强：RocketMQ 替代 Redis Stream、事务消息 / 本地消息表、压测报告
- [ ] P4 搜索：Elasticsearch 全文检索 + 数据同步
- [ ] P5 可观测：Prometheus + Grafana 监控、CI 流水线
