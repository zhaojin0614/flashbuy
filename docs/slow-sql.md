# 慢 SQL 治理案例（P5）

- 日期：2026-11-15。数据规模：tb_post 灌入 10 万行（id 31~100030，liked 随机 0~5000），实验后已清理恢复原状。
- 工具：EXPLAIN 定位执行计划；**mysqlslap**（20 次迭代）做服务端内计时——**不要用 mysql CLI 起进程计时**（客户端启动 ~35ms 固定开销会污染对比，详见踩坑）。
- 实验索引建后即删，表恢复原状（仅 PRIMARY），当前业务不受影响。

## 案例 1：热榜深分页（`ORDER BY liked DESC LIMIT 90000,10`）

### 治理前（无 liked 索引）

EXPLAIN：`type=ALL, key=NULL, rows=99187, Extra=Using filesort`（全表扫 + 排序）。
mysqlslap：**Avg 194ms**（Min 78 / Max 1094，Max 为 buffer pool 冷启动）。

### 治理尝试 B：加覆盖索引 idx_liked(liked, id)，优化器自选

EXPLAIN：**依然是 `type=ALL, filesort`——优化器拒绝用索引**。
mysqlslap：Avg 193ms——**加索引零收益**。

原因：SELECT 需要回表取 title（非覆盖），深分页 `LIMIT 90000,10` 意味着回表 90000 行——优化器估算"回表 90000 行"比"全表扫+filesort"更贵，理性拒绝。**"建了索引优化器就一定用"是误区**。

### 治理尝试 C：FORCE INDEX 强制走 idx_liked

mysqlslap：**Avg 306ms，反而比无索引更差**——回表 90000 行的随机 I/O 比 filesort 顺序扫更贵。**FORCE INDEX 不是万能药**。

### 真解法 D：游标分页（改写分页方式）

```sql
-- 上一页尾行的 liked 值作为游标（如 496）
SELECT id,title,liked FROM tb_post
WHERE liked <= 496 ORDER BY liked DESC, id DESC LIMIT 10;
```

EXPLAIN：`type=range, key=idx_liked, rows=18290, Backward index scan`——从游标值沿索引逆序取 10 行。
mysqlslap：**Avg 10ms（0~16ms），比治理前快 19 倍**。游标随页推进，每页扫描行数恒定小量；且本查询覆盖（id/liked 在索引内）无回表。

**结论：深分页的正确治理是"改写分页方式（游标/seek）+ 配套索引"，而不是"加个索引了事"——索引加了优化器都可能不用。**

## 案例 2：内容关键词搜索（`LIKE '%手办%'`）

### 治理前

EXPLAIN：`type=ALL, key=NULL, rows=99236, filtered=11.11`（全表扫，前置通配符不可能走索引）。
mysqlslap：Avg 42ms（10 万行）——**天花板问题**：LIKE 全表扫随数据量线性劣化，100 万行即 400ms+。

### 治理：FULLTEXT ngram 索引

```sql
ALTER TABLE tb_post ADD FULLTEXT INDEX ft_content (content) WITH PARSER ngram;
SELECT COUNT(*) FROM tb_post WHERE MATCH(content) AGAINST('手办' IN BOOLEAN MODE);
```

mysqlslap：**Avg 10ms（4 倍提升）**；计数与 LIKE 完全一致（41078=41078，ngram 布尔模式覆盖子串匹配）。

**结论**：FULLTEXT 是单机内解决中文子串搜索的正确阶梯；数据量到百万级/需要相关性排序时再上 Elasticsearch（与方案文档 P4 的取舍一致——本项目当前量级 FULLTEXT 即够，ES 暂缓的决策有数据支撑）。

## 应用侧改造建议（未落地，记录备查）

- 热榜接口（`queryHotPost`）当前 `ORDER BY liked DESC` + MP 物理分页——数据量大时需改游标分页（ScrollResult 已有现成结构，Feed 流同款）；
- 搜索接口保留 LIKE（当前量级 42ms 可接受），数据量增长后按案例 2 上 FULLTEXT 或 ES。

## 复现

```bash
# 灌数据（python 批量 multi-values INSERT，UTF-8 stdin，见改造记录第 46 章踩坑）
# 计时（服务端内迭代，避免客户端开销）：
mysqlslap -uroot -p*** --create-schema=flashbuy \
  --query="SELECT id,title,liked FROM tb_post ORDER BY liked DESC LIMIT 90000, 10;" \
  --iterations=20 --concurrency=1
```
