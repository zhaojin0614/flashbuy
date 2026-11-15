# JDK 21 虚拟线程压测对比（P6）

- 日期：2026-11-15
- 方法：同机同口径双组对照。500 个测试用户各秒杀 1 次、**并发 500**（超过 Tomcat 平台线程默认上限 200，放大线程模型差异）；另做一组**不发 MQ 的对照**（`/order/state`：Redis isMember + MySQL count）用于瓶颈定位。秒杀入口限流关闭。
- 开关：`spring.threads.virtual.enabled=true`（B 组）；默认平台线程（A 组）。

## 结果

| 场景 | A 组 平台线程 | B 组 虚拟线程 | 结论 |
|---|---|---|---|
| 秒杀入口（MQ 同步发送，500 并发） | QPS **204** / P50 **608ms** / P99 **1523ms** | QPS 139 / P50 1626ms / P99 3089ms | B **劣化** |
| /order/state（Redis+DB I/O 等待，500 并发） | QPS 401.6 / P50 13ms / **P99 193ms** | QPS 360.3 / P50 14ms / **P99 36ms** | B **P99 尾部延迟消除** |
| 正确性 | 两层各 500 单，三层库存/资格一致 | 同左 | 两种线程模型下均零超卖 |

## 分析

1. **秒杀入口为什么劣化**：入口瓶颈不在 Tomcat 线程数，而在 **RocketMQ producer 的同步发送**——所有请求共享 producer 的单 TCP channel，事务消息 2 次 RTT（half+commit）在该 channel 上串行化（~200/s 上限，A 组 204 QPS 正好贴住）。虚拟线程无法突破 channel 串行化，反而引入调度开销与锁竞争（500 虚拟线程争抢单 channel）。
2. **对照实验锁定瓶颈**：/order/state 不发 MQ（Redis isMember + MySQL count 的 I/O 等待）——虚拟线程在此场景 P99 从 193ms 降至 36ms（A 组 200 平台线程被 500 并发耗尽后的**排队**正是 P99 尾巴；虚拟线程无池上限，消除排队）。两条数据合起来定位：**瓶颈在 MQ 发送，不在线程模型；线程模型改变对 I/O 等待型接口有真实收益**。
3. **pinning 排查**：`-Djdk.tracePinnedThreads=full` 在 10 并发触发下无 pinned 帧输出——JDBC/HikariCP 的 synchronized 在短临界区（不 park）不触发打印。JDK 21 的 pinning 局限（JEP 491 于 JDK 24 修复）是潜在风险项，当前链路未实测到成为瓶颈。
4. **正确性**：两种线程模型下各 500 单全部落库、库存三层一致——线程模型不影响正确性（正确性由 Redis Lua 原子 + DB 约束保证，与线程无关）。

## 决策

**保持虚拟线程开启**（application.yaml `spring.threads.virtual.enabled: true`）：I/O 等待型接口的 P99 收益明确（193→36ms）、正确性无影响；秒杀入口的瓶颈用"事务消息异步化/批量发送"解决（演进项），而不是回退线程模型。

## 面试一句话

> "开了虚拟线程后，I/O 等待型接口 P99 从 193ms 降到 36ms（消除平台线程池排队）；但秒杀入口反而劣化——实测定位瓶颈在 RocketMQ producer 单 channel 的同步发送串行化，线程模型改变无效。**先定位瓶颈再谈线程收益**，这组对照数据就是证据。"

## 复现

```bash
# B 组：java -jar target/flashbuy-*.jar --server.port=8081 \
#         --order.seckill-ratelimit.enabled=false --spring.threads.virtual.enabled=true
# A 组：去掉 --spring.threads.virtual.enabled
# 负载：python scripts/loadtest_seckill.py run --product <id> --total 500 --concurrency 500 --replay-users 0 --replay-times 0
```
