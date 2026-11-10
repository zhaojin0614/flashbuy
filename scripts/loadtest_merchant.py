"""商户详情缓存压测脚本（同机对比缓存开/关）。

用法：
  python scripts/loadtest_merchant.py --url http://127.0.0.1:8082/merchant/1 --total 200 --concurrency 20
"""

import argparse
import concurrent.futures
import json
import statistics
import threading
import time

import requests


_local = threading.local()


def _session():
    session = getattr(_local, "session", None)
    if session is None:
        session = requests.Session()
        _local.session = session
    return session


def _one(url: str) -> float:
    start = time.perf_counter()
    response = _session().get(url, timeout=5)
    response.raise_for_status()
    response.content
    return (time.perf_counter() - start) * 1000


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", required=True)
    parser.add_argument("--total", type=int, default=200)
    parser.add_argument("--concurrency", type=int, default=20)
    args = parser.parse_args()

    latencies = []
    started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        futures = [pool.submit(_one, args.url) for _ in range(args.total)]
        for future in concurrent.futures.as_completed(futures):
            latencies.append(future.result())
    elapsed = time.perf_counter() - started

    latencies.sort()
    p99 = latencies[max(0, int(len(latencies) * 0.99) - 1)]
    result = {
        "url": args.url,
        "total": args.total,
        "concurrency": args.concurrency,
        "elapsedSeconds": round(elapsed, 3),
        "qps": round(args.total / elapsed, 1),
        "avgMs": round(statistics.mean(latencies), 2),
        "p99Ms": round(p99, 2),
        "minMs": round(latencies[0], 2),
        "maxMs": round(latencies[-1], 2),
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
