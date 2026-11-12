#!/usr/bin/env python3
"""Bounded multi-account HTTP qualification; checks every aggregate after durable propagation."""
import argparse
import concurrent.futures
import copy
import json
import math
import random
import time
import urllib.request
import uuid
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--url', default='http://127.0.0.1:8080')
parser.add_argument('--examples', type=Path, default=Path(__file__).resolve().parents[1] / 'examples')
parser.add_argument('--accounts', type=int, default=8)
parser.add_argument('--orders', type=int, default=8)
parser.add_argument('--operations', type=int, default=32)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
if not (1 <= args.accounts <= 64 and 1 <= args.orders <= 32 and 1 <= args.operations <= 128):
    parser.error('Bounded dimensions: accounts 1..64, orders 1..32, operations 1..128')
run = 'parallel-' + str(uuid.uuid4())
agreement = (args.examples / 'agreement.blue').read_text()
order_template = json.loads((args.examples / 'order.json').read_text())
payment_names = list(order_template['payments'])

def post(endpoint, account, document='agreement', **values):
    body = {'account': account, 'document': document, **values}
    request = urllib.request.Request(args.url.rstrip('/') + endpoint, data=json.dumps(body).encode(),
        headers={'Content-Type': 'application/json'}, method='POST')
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)

def get(endpoint):
    with urllib.request.urlopen(args.url.rstrip('/') + endpoint, timeout=30) as response:
        return json.load(response)

def applied(result):
    if result['status'] != 'APPLIED':
        raise RuntimeError('Unexpected business outcome: ' + json.dumps(result))
    return result

def await_total(account, field, expected):
    deadline = time.monotonic() + 300
    while time.monotonic() < deadline:
        state = post('/documents/read', account, expanded=False)
        if state['data'][field] == expected:
            return state['version']
        if state['data'][field] > expected:
            raise RuntimeError('Duplicate effects: ' + account)
        time.sleep(.1)
    raise RuntimeError('Propagation timeout: ' + account + '/' + field)

def setup(index):
    account = run + '-' + str(index)
    applied(post('/documents/create', account, command='create', source=agreement))
    for order_index in range(args.orders):
        order = copy.deepcopy(order_template)
        order['key'] = 'o' + str(order_index)
        applied(post('/documents/call', account, command='add-' + str(order_index),
                     channel='input', operation='addOrder',
                     request={'key': order['key'], 'order': order}, timestamp=order_index + 1))
    await_total(account, 'initializedOrders', args.orders)
    payments = []
    for order_index in range(args.orders):
        for payment in payment_names:
            payments.append(post('/documents/embedded', account,
                path='/orders/o' + str(order_index) + '/payments/' + payment)['document'])
    return account, payments

def exercise(entry):
    account, payments = entry
    rng = random.Random(1729)
    local, commits = [], []
    started = time.perf_counter()
    for operation in range(args.operations):
        target = rng.choice(payments)
        before = time.perf_counter()
        result = applied(post('/documents/call', account, document=target,
            command='record-' + str(operation), channel='input', operation='record',
            request={'sequence': operation}, timestamp=args.orders + operation + 1))
        commits.append((time.perf_counter() - before) * 1000)
        local.append(result['executionNanos'] / 1e6)
    version = await_total(account, 'paidTotal', 42 * args.operations)
    return {'account': account, 'leafExecutionMillis': local, 'leafCommitMillis': commits,
            'completedMillis': (time.perf_counter() - started) * 1000, 'rootVersion': version,
            'paidTotal': 42 * args.operations}

def percentile(values, fraction):
    return sorted(values)[math.ceil(len(values) * fraction) - 1]

setup_started = time.perf_counter()
with concurrent.futures.ThreadPoolExecutor(max_workers=args.accounts) as clients:
    prepared = list(clients.map(setup, range(args.accounts)))
    setup_seconds = time.perf_counter() - setup_started
    health = get('/health')
    metrics_before = get('/metrics')
    started = time.perf_counter()
    results = list(clients.map(exercise, prepared))
    elapsed = time.perf_counter() - started
    metrics_after = get('/metrics')
commits = [sample for result in results for sample in result['leafCommitMillis']]
local = [sample for result in results for sample in result['leafExecutionMillis']]
report = {'run': run, 'accounts': args.accounts, 'health': health,
          'metricsBefore': metrics_before, 'metricsAfter': metrics_after, 'ordersPerAccount': args.orders,
          'paymentsPerOrder': len(payment_names), 'operationsPerAccount': args.operations,
          'documents': args.accounts * (1 + args.orders * (1 + len(payment_names))),
          'setupSeconds': setup_seconds, 'completionSeconds': elapsed,
          'completedOperationsPerSecond': args.accounts * args.operations / elapsed,
          'leafCommitMedianMillis': percentile(commits, .5), 'leafCommitP95Millis': percentile(commits, .95),
          'leafExecutionMedianMillis': percentile(local, .5), 'results': results,
          'method': 'Concurrent accounts; setup and target lookup excluded; all root totals checked; no warm-up exclusion'}
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps({key: value for key, value in report.items() if key not in ('results', 'metricsBefore', 'metricsAfter')}, indent=2))
