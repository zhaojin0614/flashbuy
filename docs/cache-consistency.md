# 缓存一致性、热点与大 Key 笔记（P2 Phase 2）

## 1. 当前缓存结构

```text
请求 -> Caffeine L1（120 秒，最多 1 万条）
      -> Redis L2（详情 30 分钟，列表 2 分钟）
      -> MySQL
```

- 详情：`cache:merchant:{id}`，L1 + L2；
- 列表：`cache:merchant:list:{typeId}:{sort}:{current}`，只缓存 `score` / `comments` 排序；
- `distance`（附近）依赖用户坐标，不做页面缓存，仍走 Redis GEO。

更新路径：

```text
更新 MySQL
  -> 删除详情 Redis + 本机 L1
  -> 删除该分类的列表 Redis + 本机 L1
  -> Phase 3 追加 Pub/Sub 广播和延迟双删
```

## 2. 一致性方案对比

| 方案 | 优点 | 缺点 | 本项目取舍 |
|---|---|---|---|
| 先更新 DB 再删缓存 | 简单、常用 | 并发读可能回填旧值 | 采用，配合延迟双删 |
| 先删缓存再更新 DB | 减少旧值窗口 | 更新失败会丢缓存 | 不采用 |
| 延迟双删 | 覆盖并发回填 | 延迟时间难精确，多一次删除 | 已实现（1 秒后二次删除 + 广播） |
| 短 TTL | 实现最简单 | 存在不一致窗口 | L1 120 秒兜底 |
| Redis Pub/Sub 失效 L1 | 多实例及时失效 | 消息可能丢，需要 TTL 兜底 | 已实现 |
| Canal 订阅 binlog | 与业务解耦、可靠 | 引入 Canal，运维复杂 | 作为对比方案，不落地 |

结论：缓存不做强一致，目标是**最终一致**。当前用“DB 优先 + 删除 L1/L2 + L1 短 TTL”，Phase 3 再加 Pub/Sub 与延迟双删。

## 3. 热点 Key 探测

- 统计接口：`GET /merchant/cache/stats`
- 返回内容：
  - 详情/列表 L1 的 size、hitRate、hitCount、missCount、evictionCount；
  - `hotMerchants`：本机访问次数 Top5。
- 实测（Phase 2 验证时）：

```json
{
  "detail": {"size": 1, "hitRate": 0.5, "hitCount": 1, "missCount": 1},
  "list": {"size": 1, "hitRate": 0.5, "hitCount": 1, "missCount": 1},
  "hotMerchants": [{"id": 1, "access": 2}]
}
```

热点数据天然由 Caffeine L1 兜底；计数器仅用于演示和排查，不参与缓存正确性。多实例下每个实例各有一份 Top5，生产可换 Redis LFU（`OBJECT FREQ`）或集中式指标。

## 4. 大 Key 排查

常用命令：

```bash
# 扫描整个实例的大 key（SCAN 实现，不阻塞）
redis-cli --bigkeys

# 单 key 的内存占用与长度
redis-cli MEMORY USAGE cache:merchant:list:1:score:2
redis-cli STRLEN cache:merchant:list:1:score:2
```

实测结果（Phase 2 验证时）：

```text
MEMORY USAGE cache:merchant:list:1:score:2 = 1307 bytes
STRLEN       cache:merchant:list:1:score:2 = 1245
```

当前缓存 key 都是小 JSON，没有大 Key 风险；如果后续缓存整页大对象，应设置阈值、拆分 key 或压缩。

## 5. 多实例失效（Phase 3 已实现）

- L1 是进程内缓存，每个实例各一份；
- 更新时删除详情/分类列表的 Redis 与本机 L1，并向 `cache:invalidate:merchant` 发布 `id:typeId`；
- 其他实例的 `MerchantCacheInvalidationSubscriber` 收到消息后删除自己的 L1；
- 1 秒后再删一次（本地 + Redis + 广播），覆盖并发读回填的旧值；
- Pub/Sub 消息可能丢，L1 TTL（120 秒）仍是最后兜底。

双实例实测（8082 更新、8083 读取）：

| 8083 请求时机 | SQL 条数 | 说明 |
|---|---|---|
| 更新后 0.3 秒首次 GET | 1 | Pub/Sub 已删本机 L1，回源 DB |
| 紧接着第二次 GET | 0 | L1 已重新预热 |
| 1 秒延迟双删后再 GET | 1 | 第二轮失效生效 |
| 紧接着再 GET | 0 | L1 再次预热 |

压测数字见 `docs/cache-loadtest.md`。

## 6. 列表缓存不做距离排序的原因

`sortBy=distance` 的结果依赖用户 `x/y` 坐标，不同用户/位置结果不同；按坐标做缓存 key 会导致 key 爆炸且命中率低。因此 Phase 2 只缓存 `score` / `comments` 的稳定排序，距离排序继续实时查 Redis GEO。
