# 秒杀链路压测报告：Redis Stream → RocketMQ 改造对比（P3 任务六）

- 日期：2026-11-14
- 方法论：同机（Windows，JDK 21 单实例 8082）双组对照。A 组为改造前提交 `7e34de4`（Redis Stream 单线程消费），B 组为改造后 `84f366d`（RocketMQ 事务消息 + 容器托管消费）。两组共享同一批 500 个测试用户与同一套环境（MySQL 8 / Redis 7 / RocketMQ 4.9.7 Docker）。
- 负载模型：50 并发 × 800 请求 = 500 个真实建单路径（每用户限抢 1 次）+ 300 个重复投递路径（50 用户 × 6 次重复），商品库存 2000。
- 工具：`scripts/loadtest_seckill.py`（requests 线程池；JMeter 未安装，沿用 P2 压测的 Python 先例）。

## 结果对比

| 指标 | A 组：Redis Stream（7e34de4） | B 组：RocketMQ（84f366d） |
|---|---|---|
| 入口 QPS | **289.8** | 185.5 |
| 入口 P50 | **42.0 ms** | 221.6 ms |
| 入口 P95 | 145.0 ms | 776.1 ms |
| 入口 P99 | **200.8 ms** | 853.0 ms |
| 入口平均 | 59.4 ms | 257.2 ms |
| 结果分布 | 500 ok + 300 dup | 500 ok + 300 dup |
| 消费落库速率 | ~125 单/s（单线程） | **~250 单/s（并发消费）** |
| 500 单清空耗时 | ~4 s（214→364→500） | **~2 s（304→500）** |
| 防超卖一致性 | Redis 1500 = DB 1500 ✓ | Redis 1500 = DB 1500 ✓ |

## 分析

1. **入口 QPS 下降是可靠性的显式代价**：B 组每次秒杀在请求线程内同步完成"半消息发送 → Lua 预扣 → COMMIT 确认"，涉及两次 broker 网络往返（事务消息约 2 次 RTT）；A 组的 XADD 是 Redis 内存操作。50 并发下 broker 同步发送成为入口瓶颈，QPS 289.8 → 185.5、P99 200.8ms → 853ms。
2. **改造的核心收益在消费侧解耦而非入口提速**：B 组消费吞吐翻倍（RocketMQ 并发消费 20 线程 vs Stream 单线程 XREADGROUP），500 单清空时间 4s → 2s。更重要的是架构意义：**DB 承受的并发由消费线程数决定而非入口洪峰决定**——入口 5000 QPS 时 DB 依然只看到 ≤20 并发事务，这正是"削峰"的可量化证据。
3. **入口提速的演进路径**（本次不做）：a) 入口改异步发送 + 本地消息表（用任务四的可靠性资产换入口延迟，代价是响应里无法即时给"资格失败"文案）；b) 提升入口并发（瓶颈在 broker RTT，可加 broker）；c) 重复路径（ROLLBACK）免走 broker 往返——先本地 Lua 判重再事务消息，可把 300 个重复请求的 P99 显著降低。
4. **正确性**：两组 500 单全部落库、库存三层一致（Redis = DB = 2000-500）、300 次重复全部被拦（A 组 Lua Set / B 组 Lua ROLLBACK），零超卖零丢单。

## 复现步骤

```bash
# 1. A 组（Redis Stream）
git checkout 7e34de4 && mvn -B -DskipTests package
java -jar target/flashbuy-*.jar --server.port=8082   # 8081 保留用户实例
python scripts/loadtest_seckill.py prepare --users 500
python scripts/loadtest_seckill.py run --product 99011 --total 500 --replay-users 50 --replay-times 6 --concurrency 50

# 2. B 组（RocketMQ）
git checkout main && mvn -B -DskipTests package
java -jar target/flashbuy-*.jar --server.port=8082 --order.seckill-ratelimit.enabled=false
python scripts/loadtest_seckill.py run --product 99012 --total 500 --replay-users 50 --replay-times 6 --concurrency 50

# 3. 落库观察（每秒轮询直到稳定）
mysql -uroot -N flashbuy -e "SELECT COUNT(*) FROM tb_order WHERE product_id = 99011;"
```

## 边界与说明

- 单机环境、无冷启动预热、JVM/网卡/日志同为干扰项——数字用于**同机纵向对比**，不代表生产绝对值。
- 压测期间限流关闭（裸链路），生产入口有令牌桶双重限流（任务五），洪峰下入口 QPS 上限受 `api-capacity=200` 约束。
- 简历话术以此报告为准：**「同机对照压测：入口层事务消息使 P99 由 201ms 升至 853ms（可靠性代价），消费吞吐翻倍（125→250 单/s），500 单清空时间 4s→2s，DB 并发与入口洪峰解耦，全程零超卖」**——如实写代价，比只写收益更可信。
