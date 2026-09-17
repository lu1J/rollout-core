#!/usr/bin/env python3
"""Standard-library closed-loop HTTP load driver; no retries or fixture creation."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import http.client
import json
import math
import platform
import threading
import time
from urllib.parse import urlsplit


def percentile(values, percent):
    """Nearest-rank percentile; every completed attempt is included."""
    return sorted(values)[max(0, math.ceil(len(values) * percent / 100) - 1)] if values else None


def run(args, count):
    url = urlsplit(args.base_url)
    template = json.loads(args.context)
    barrier = threading.Barrier(args.concurrency + 1)

    def worker(worker_id):
        connection_type = http.client.HTTPSConnection if url.scheme == 'https' else http.client.HTTPConnection
        connection = connection_type(url.hostname, url.port, timeout=args.timeout)
        latencies, statuses = [], {}
        successes = 0
        barrier.wait()
        try:
            # Round-robin indices avoid an unbounded queue of futures.
            for index in range(worker_id, count, args.concurrency):
                context = dict(template)
                context['userId'] = args.user_template.replace('{i}', str(index))
                body = json.dumps({'projectKey': args.project, 'environmentKey': args.environment,
                                   'flagKey': args.flag, 'context': context}).encode('utf-8')
                begin = time.perf_counter()
                status = 'transport_error'
                try:
                    connection.request('POST', url.path.rstrip('/') + '/api/v1/evaluate', body,
                                       {'Content-Type': 'application/json'})
                    response = connection.getresponse()
                    payload = response.read()
                    status = str(response.status)
                    if response.status == 200:
                        status = 'invalid_response'
                        parsed = json.loads(payload)
                        if (isinstance(parsed, dict) and parsed.get('flagKey') == args.flag
                                and isinstance(parsed.get('configVersion'), int)
                                and 'value' in parsed and 'reason' in parsed):
                            successes += 1
                            status = '200'
                        else:
                            status = 'invalid_response'
                except (OSError, http.client.HTTPException, ValueError):
                    connection.close()  # Next attempt reconnects; never retry an attempt.
                latencies.append((time.perf_counter() - begin) * 1000)
                statuses[status] = statuses.get(status, 0) + 1
        finally:
            connection.close()
        return latencies, successes, statuses

    with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        futures = [pool.submit(worker, i) for i in range(args.concurrency)]
        started = time.perf_counter()
        barrier.wait()
        results = [future.result() for future in futures]
        elapsed = time.perf_counter() - started
    latencies = [value for result in results for value in result[0]]
    successes = sum(result[1] for result in results)
    statuses = {}
    for _, _, counts in results:
        for status, number in counts.items(): statuses[status] = statuses.get(status, 0) + number
    return {'requests': len(latencies), 'success': successes, 'errors': len(latencies) - successes,
            'duration_seconds': elapsed, 'qps': len(latencies) / elapsed,
            'success_qps': successes / elapsed,
            'p50_ms': percentile(latencies, 50), 'p95_ms': percentile(latencies, 95),
            'p99_ms': percentile(latencies, 99), 'statuses': statuses}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://127.0.0.1:8080')
    parser.add_argument('--concurrency', type=int, default=8)
    parser.add_argument('--requests', type=int, default=10000)
    parser.add_argument('--warmup', type=int, default=500, help='Excluded from measured results')
    parser.add_argument('--project', required=True)
    parser.add_argument('--environment', default='prod')
    parser.add_argument('--flag', default='new-payment-flow')
    parser.add_argument('--user-template', default='load-{i}', help='{i} is the attempt index')
    parser.add_argument('--context', default='{}', help='JSON object; userId comes from --user-template')
    parser.add_argument('--timeout', type=float, default=5)
    args = parser.parse_args()
    url = urlsplit(args.base_url)
    try:
        url.port
    except ValueError:
        parser.error('invalid URL port')
    if (url.scheme not in ('http', 'https') or not url.hostname or url.username or url.password
            or url.query or url.fragment): parser.error('base URL must be HTTP(S), without credentials/query/fragment')
    if args.requests < 1 or args.concurrency < 1 or args.warmup < 0 or not math.isfinite(args.timeout) or args.timeout <= 0:
        parser.error('requests/concurrency/timeout must be positive; warmup must be nonnegative')
    try:
        if not isinstance(json.loads(args.context), dict): raise ValueError()
    except ValueError:
        parser.error('context must be a JSON object')
    warmup = run(args, args.warmup) if args.warmup else None
    if warmup and warmup['errors']:
        print(json.dumps({'warmup': warmup, 'measurement': 'NOT_RUN: warmup failed'}, indent=2))
        return 1
    result = run(args, args.requests)
    print(json.dumps({'scenario': 'repeated single-flag HTTP evaluation',
                      'client': platform.platform(), 'python': platform.python_version(),
                      'concurrency': args.concurrency, 'warmup_requests': args.warmup,
                      'warmup': warmup, 'measurement': result}, indent=2))
    return 1 if result['errors'] else 0


if __name__ == '__main__':
    raise SystemExit(main())
