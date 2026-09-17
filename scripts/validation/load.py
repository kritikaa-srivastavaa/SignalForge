"""Small concurrent HTTP probe, not a statistically rigorous benchmark (stdlib only)."""
import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import json
import math
from pathlib import Path
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
import uuid


def percentile(values, percent):
    """Linear interpolation between sorted observations; None for an empty sample."""
    if not values:
        return None
    ordered = sorted(values)
    position = (len(ordered) - 1) * percent / 100
    low, high = math.floor(position), math.ceil(position)
    return ordered[low] + (ordered[high] - ordered[low]) * (position - low)


def latency_stats(values):
    if not values:
        return {key: None for key in ("min", "average", "p50", "p95", "p99", "max")}
    return dict(min=min(values), average=sum(values) / len(values),
                p50=percentile(values, 50), p95=percentile(values, 95),
                p99=percentile(values, 99), max=max(values))


def summarize(results, elapsed):
    counts = Counter(str(row["status"]) for row in results if row["status"] is not None)
    accepted = counts.get("201", 0)
    return {
        "attempted": len(results), "accepted_201": accepted,
        "rejected_429": counts.get("429", 0), "status_counts": dict(counts),
        "request_failures": sum(row["status"] is None for row in results),
        "elapsed_seconds": elapsed,
        "accepted_per_second": accepted / elapsed if elapsed > 0 else 0,
        "latency_ms": latency_stats([row["latency_ms"] for row in results]),
        "accepted_latency_ms": latency_stats([row["latency_ms"] for row in results if row["status"] == 201]),
    }


def request(url, method="GET", payload=None, timeout=200):
    start = time.perf_counter()
    body = None if payload is None else json.dumps(payload).encode("utf-8")
    headers = {} if body is None else {"Content-Type": "application/json"}
    try:
        try:
            response = urlopen(Request(url, data=body, headers=headers, method=method), timeout=timeout)
        except HTTPError as error:
            response = error  # HTTP failures are responses, not transport failures.
        with response:
            raw = response.read().decode("utf-8", errors="replace")
            try:
                value = json.loads(raw)
            except ValueError:
                value = None
            return {"status": response.code, "latency_ms": (time.perf_counter() - start) * 1000,
                    "response": value}
    except (URLError, TimeoutError, OSError) as error:
        return {"status": None, "latency_ms": (time.perf_counter() - start) * 1000,
                "error": type(error).__name__}


def run(total=20, concurrency=4, service=None, event_type="LOAD_TEST", severity="HIGH",
        base_url="http://localhost:8080", timeout=200, timestamp=None):
    if total < 1 or concurrency < 1 or timeout <= 0:
        raise ValueError("total, concurrency and timeout must be positive")
    service = service or "load-validation-" + uuid.uuid4().hex[:12]
    run_id = uuid.uuid4().hex[:12]

    def send(index):
        payload = {"service": service, "type": event_type, "severity": severity,
                   "message": f"{run_id}-{index}",
                   "timestamp": timestamp or datetime.now(timezone.utc).isoformat()}
        result = request(base_url.rstrip("/") + "/events", "POST", payload, timeout)
        # Keep request identities and accepted IDs for asynchronous/database reconciliation.
        return {"request": payload, **result}

    start = time.perf_counter()
    with ThreadPoolExecutor(max_workers=concurrency) as pool:
        results = list(pool.map(send, range(total)))
    return {"service": service, "concurrency": concurrency,
            **summarize(results, time.perf_counter() - start), "results": results}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--requests", type=int, default=20)
    parser.add_argument("--concurrency", type=int, default=4)
    parser.add_argument("--service")
    parser.add_argument("--type", dest="event_type", default="LOAD_TEST")
    parser.add_argument("--severity", default="HIGH")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--timeout", type=float, default=200)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        result = run(args.requests, args.concurrency, args.service, args.event_type,
                     args.severity, args.base_url, args.timeout)
    except ValueError as error:
        parser.error(str(error))
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in result.items() if key != "results"}, indent=2))


if __name__ == "__main__":
    main()