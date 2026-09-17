"""Tool correctness only: local HTTP fixture, never a performance benchmark."""
import argparse
import importlib.util
import json
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import subprocess
import sys
import threading
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'load-test.py'
spec = importlib.util.spec_from_file_location('load_test', SCRIPT)
load = importlib.util.module_from_spec(spec)
spec.loader.exec_module(load)


class LoadToolTest(unittest.TestCase):
    def setUp(self):
        self.bodies = []
        self.status = 200
        self.payload = b'{"flagKey":"pay","configVersion":1,"value":true,"reason":"DEFAULT"}'
        case = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = 'HTTP/1.1'

            def do_POST(self):
                case.bodies.append(json.loads(self.rfile.read(int(self.headers['Content-Length']))))
                self.send_response(case.status)
                self.send_header('Content-Length', str(len(case.payload)))
                self.end_headers()
                self.wfile.write(case.payload)

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.args = argparse.Namespace(base_url=f'http://127.0.0.1:{self.server.server_port}',
            concurrency=3, timeout=1, project='shop', environment='prod', flag='pay',
            user_template='user-{i}', context='{"country":"CN"}')

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def test_exact_count_context_and_percentiles(self):
        result = load.run(self.args, 7)
        self.assertEqual((7, 7, 0), (result['requests'], result['success'], result['errors']))
        self.assertEqual({f'user-{i}' for i in range(7)}, {b['context']['userId'] for b in self.bodies})
        self.assertTrue(all(b['context']['country'] == 'CN' for b in self.bodies))
        self.assertLessEqual(result['p50_ms'], result['p95_ms'])
        self.assertLessEqual(result['p95_ms'], result['p99_ms'])
        self.assertEqual(95, load.percentile(list(range(1, 101)), 95))

    def test_http_and_protocol_errors_are_not_successes(self):
        self.status = 503
        result = load.run(self.args, 4)
        self.assertEqual((0, 4, {'503': 4}), (result['success'], result['errors'], result['statuses']))
        self.status = 200
        self.payload = b'not-json'
        result = load.run(self.args, 4)
        self.assertEqual(4, result['errors'])
        self.assertEqual({'invalid_response': 4}, result['statuses'])

    def test_warmup_excluded_and_failure_aborts_measurement(self):
        command = [sys.executable, str(SCRIPT), '--base-url', self.args.base_url,
                   '--project', 'shop', '--flag', 'pay', '--warmup', '2', '--requests', '3', '--concurrency', '2']
        result = subprocess.run(command, capture_output=True, text=True, timeout=15)
        self.assertEqual(0, result.returncode, result.stderr)
        output = json.loads(result.stdout)
        self.assertEqual(2, output['warmup']['requests'])
        self.assertEqual(3, output['measurement']['requests'])
        self.assertEqual(5, len(self.bodies))
        self.status = 503
        result = subprocess.run(command, capture_output=True, text=True, timeout=15)
        self.assertEqual(1, result.returncode)
        self.assertTrue(json.loads(result.stdout)['measurement'].startswith('NOT_RUN'))
        self.assertEqual(7, len(self.bodies))


if __name__ == '__main__':
    unittest.main()
