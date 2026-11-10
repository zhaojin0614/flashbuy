# 商户缓存压测报告（P2 Phase 3）

## 1. 环境与工具

- 应用：Spring Boot 3.5.16 / JDK 21，单实例 8082；
- 数据：本地 MySQL 8 / Redis 7，`/merchant/1` 商户详情；
- 工具：本机未安装 JMeter / ab / wrk，使用 `scripts/loadtest_merchant.py`（Python requests + 每线程 Session，连接复用）替代；
- 参数：200 请求，20 并发，同一台机器、同一接口；
- 说明：Python 客户端有 GIL 和 HTTP 开销，绝对 QPS 会低于 JMeter 等专业工具；本次重点看“同口径下缓存开关的相对差异”和 DB SQL 次数。

## 2. 结果

| 场景 | QPS | 平均耗时 | P99 | 压测期间 SQL 条数 |
|---|---|---|---|---|
| 缓存关闭 `cache.merchant.enabled=false` | 290.9 | 63.65 ms | 420.70 ms | 200 |
| 缓存开启（预热后） | 411.0 | 44.81 ms | 181.34 ms | 0 |

- QPS 提升约 **41%**；
- P99 降低约 **57%**；
- DB SQL 从每请求 1 条降到 0 条，说明请求全部命中 Caffeine L1；
- 缓存开启后的 stats：

```json
{
  "detail": {
    "size": 1,
    "hitCount": 201,
    "missCount": 1,
    "hitRate": 0.995
  },
  "hotMerchants": [{"id": 1, "access": 202}]
}
```

## 3. 复现命令

缓存关闭：

```bash
java -jar target/flashbuy-0.0.1-SNAPSHOT.jar \
  --server.port=8082 --cache.merchant.enabled=false

python scripts/loadtest_merchant.py \
  --url http://127.0.0.1:8082/merchant/1 --total 200 --concurrency 20
```

缓存开启：

```bash
java -jar target/flashbuy-0.0.1-SNAPSHOT.jar --server.port=8082

# 预热一次，让 L1/L2 有数据
curl -s http://127.0.0.1:8082/merchant/1 >/dev/null

python scripts/loadtest_merchant.py \
  --url http://127.0.0.1:8082/merchant/1 --total 200 --concurrency 20
```

## 4. 解读与边界

1. **为什么 QPS 只提升 41%**：本地单机、数据量小、Python 客户端本身是瓶颈；DB 查询不是唯一成本，HTTP/线程/日志也在耗时。压测期间缓存关闭会打印 200 条 SQL 日志，日志 I/O 也拖慢了它。
2. **为什么 P99 改善明显**：缓存关闭时每个请求都要经过连接池和 MySQL；开启后绝大多数请求直接命中 L1，尾部延迟显著下降。
3. **为什么 SQL 是 0 而不是 1**：benchmark 前有预热请求，200 次压测请求全部命中 Caffeine L1；第一次预热请求的 SQL 不在统计区间内。
4. **生产压测建议**：用 JMeter/wrk 在多机环境压测，记录服务端 QPS、P99、DB QPS、Redis QPS、GC 和连接池指标；本次是本地演示，不是生产容量结论。
5. **缓存一致性**：跨实例 L1 失效与延迟双删的验证见 `docs/cache-consistency.md`。
