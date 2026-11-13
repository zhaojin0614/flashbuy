"""秒杀链路压测脚本（P3 任务六，同机对比 Redis Stream 与 RocketMQ 两组链路）。

用法（三步）：
  python scripts/loadtest_seckill.py prepare --base http://127.0.0.1:8082 --users 500 --phones-prefix 13900092
  python scripts/loadtest_seckill.py run     --base http://127.0.0.1:8082 --tokens tokens_seckill.json --product 99011 --total 500 --replay-users 50 --replay-times 6 --concurrency 50
  # 落库观察用 mysql 轮询（见 docs/seckill-loadtest.md）

说明：
- prepare：批量注册/登录测试用户（验证码从 Redis 读取，演示环境日志/Redis 明文存储），
  输出 tokens JSON 文件；验证码限流 1 次/60s 按手机号隔离，互不影响。
- run：50 并发发起秒杀——前 total 个请求为"每用户 1 发"的真实建单路径（一人一单），
  随后 replay-users × replay-times 为"重复投递"路径（Lua ROLLBACK / 重复拦截）。
  输出 QPS、P50/P95/P99、结果分布 JSON。
"""

import argparse
import concurrent.futures
import json
import statistics
import threading
import time

import redis
import requests

_local = threading.local()
_counter_lock = threading.Lock()
_counters = {"ok": 0, "dup": 0, "rate_limited": 0, "stock_out": 0, "other": 0}


def _session() -> requests.Session:
    session = getattr(_local, "session", None)
    if session is None:
        session = requests.Session()
        _local.session = session
    return session


def prepare(base: str, users: int, phones_prefix: str, redis_host: str) -> str:
    out_path = "tokens_seckill.json"
    r = redis.Redis(host=redis_host, port=6379, db=0, decode_responses=True)
    tokens = {}
    prefix = phones_prefix[:len(phones_prefix) - (users and len(str(users)) or 0)]
    for i in range(users):
        phone = f"{phones_prefix}{i:03d}"[-11:]
        s = requests.Session()
        resp = s.post(f"{base}/user/code?phone={phone}", timeout=5)
        resp.raise_for_status()
        time.sleep(0.35)
        code = r.get(f"login:code:{phone}")
        assert code, f"验证码未写入 Redis：{phone}"
        resp = s.post(f"{base}/user/login", json={"phone": phone, "code": code}, timeout=5)
        resp.raise_for_status()
        body = resp.json()
        assert body["success"], f"登录失败：{phone} {body}"
        tokens[phone] = body["data"]["accessToken"]
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(tokens, f)
    print(f"prepared {len(tokens)} users -> {out_path}")
    return out_path


def _seckill(base: str, product: int, token: str) -> float:
    start = time.perf_counter()
    response = _session().post(
        f"{base}/order/seckill/{product}",
        headers={"authorization": token},
        timeout=10,
    )
    latency_ms = (time.perf_counter() - start) * 1000
    body = response.json()
    if body.get("success"):
        _counters["ok"] += 1
    else:
        msg = body.get("errorMsg", "")
        if "重复" in msg:
            _counters["dup"] += 1
        elif "频繁" in msg:
            _counters["rate_limited"] += 1
        elif "库存不足" in msg:
            _counters["stock_out"] += 1
        else:
            _counters["other"] += 1
    return latency_ms


def run(base: str, tokens_path: str, product: int, total: int,
        replay_users: int, replay_times: int, concurrency: int) -> None:
    with open(tokens_path, encoding="utf-8") as f:
        tokens = list(json.load(f).values())
    assert len(tokens) >= total, f"tokens 不足：{len(tokens)} < {total}"
    assert len(tokens) >= replay_users, f"tokens 不足 replay：{len(tokens)} < {replay_users}"

    tasks = [(t, i) for i, t in enumerate(tokens[:total])]
    tasks += [(tokens[i], -1) for i in range(replay_users) for _ in range(replay_times)]
    latencies = []
    lock = threading.Lock()

    def worker(task):
        token, _ = task
        latency = _seckill(base, product, token)
        with lock:
            latencies.append(latency)

    start = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as pool:
        list(pool.map(worker, tasks))
    wall = time.perf_counter() - start

    latencies.sort()
    report = {
        "requests": len(tasks),
        "wall_seconds": round(wall, 2),
        "qps": round(len(tasks) / wall, 1),
        "p50_ms": round(statistics.median(latencies), 1),
        "p95_ms": round(latencies[int(len(latencies) * 0.95) - 1], 1),
        "p99_ms": round(latencies[int(len(latencies) * 0.99) - 1], 1),
        "avg_ms": round(statistics.fmean(latencies), 1),
        "result_dist": dict(_counters),
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))
    with open("result_seckill.json", "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)


def main() -> None:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="phase", required=True)
    p1 = sub.add_parser("prepare")
    p1.add_argument("--base", default="http://127.0.0.1:8082")
    p1.add_argument("--users", type=int, default=500)
    p1.add_argument("--phones-prefix", default="13900092")
    p1.add_argument("--redis-host", default="127.0.0.1")
    p2 = sub.add_parser("run")
    p2.add_argument("--base", default="http://127.0.0.1:8082")
    p2.add_argument("--tokens", default="tokens_seckill.json")
    p2.add_argument("--product", type=int, required=True)
    p2.add_argument("--total", type=int, default=500)
    p2.add_argument("--replay-users", type=int, default=50)
    p2.add_argument("--replay-times", type=int, default=6)
    p2.add_argument("--concurrency", type=int, default=50)
    args = parser.parse_args()
    if args.phase == "prepare":
        prepare(args.base, args.users, args.phones_prefix, args.redis_host)
    else:
        run(args.base, args.tokens, args.product, args.total,
            args.replay_users, args.replay_times, args.concurrency)


if __name__ == "__main__":
    main()
