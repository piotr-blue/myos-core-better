#!/usr/bin/env python3
"""One machine's part of a bounded, shared-DynamoDB concurrency qualification."""
import argparse
import concurrent.futures
import json
import statistics
import time
import urllib.error
import urllib.request
from pathlib import Path


def post(endpoint, account, document='agreement', **values):
    request = urllib.request.Request(
        'http://127.0.0.1:8080' + endpoint,
        data=json.dumps({'account': account, 'document': document, **values}).encode(),
        headers={'Content-Type': 'application/json'}, method='POST')
    with urllib.request.urlopen(request, timeout=90) as response:
        return json.load(response)


def applied(result):
    if result['status'] != 'APPLIED':
        raise RuntimeError('Unexpected business outcome: ' + json.dumps(result))
    return result


def await_total(account, field, expected):
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        state = post('/documents/read', account)
        if state['data'][field] == expected:
            return state
        if state['data'][field] > expected:
            raise RuntimeError('Duplicate aggregate effects for ' + account)
        time.sleep(.1)
    raise RuntimeError('Propagation did not complete for ' + account)


def prepare(args):
    source = (args.examples / 'agreement.blue').read_text()
    order_source = (args.examples / 'order.json').read_text()
    payment_names = list(json.loads(order_source)['payments'])
    prepared = []
    for index in range(4):
        account = args.run + '-' + str(index)
        applied(post('/documents/create', account, command='create', source=source))
        for order in range(2):
            key = 'o' + str(order)
            definition = json.loads(order_source.replace('o1', key))
            applied(post('/documents/call', account, command='add-' + key, channel='input',
                         operation='addOrder', request={'key': key, 'order': definition},
                         timestamp=order + 1))
        await_total(account, 'initializedOrders', 2)
        payments = [post('/documents/embedded', account,
                         path='/orders/o' + str(order) + '/payments/' + payment)['document']
                    for order in range(2) for payment in payment_names]
        prepared.append({'account': account, 'payments': payments})
    return {'run': args.run, 'operations': 32, 'accounts': prepared}


def race(manifest, start):
    def submit(item):
        account, document, operation = item
        started = time.perf_counter()
        result = applied(post('/documents/call', account, document,
                              command='payment-' + str(operation), channel='input', operation='record',
                              request={'sequence': operation}, timestamp=operation + 100))
        return {'account': account, 'operation': operation, 'result': result,
                'elapsedMillis': (time.perf_counter() - started) * 1000}

    if not 0 < start - time.time() <= 120:
        raise ValueError('A common start within the next two minutes is required')
    time.sleep(max(0, start - time.time()))
    requests = [(row['account'], row['payments'][operation % len(row['payments'])], operation)
                for operation in range(manifest['operations']) for row in manifest['accounts']]
    started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        outcomes = list(pool.map(submit, requests))
    return {'outcomes': outcomes, 'seconds': time.perf_counter() - started,
            'callMedianMillis': statistics.median(row['elapsedMillis'] for row in outcomes)}


def verify(manifest):
    snapshots = []
    operations = manifest['operations']
    for row in manifest['accounts']:
        account = row['account']
        await_total(account, 'paidTotal', 42 * operations)
        snapshot = post('/documents/read', account, expanded=True)
        if snapshot['data']['initializedOrders'] != 2:
            raise RuntimeError('Order initialization was duplicated or lost')
        for order in snapshot['data']['orders'].values():
            if order['paidTotal'] != 42 * operations // 2:
                raise RuntimeError('Order aggregate mismatch')
            for payment in order['payments'].values():
                if payment['recordedAmount'] != 42 * operations // len(row['payments']):
                    raise RuntimeError('Payment was lost or counted twice')
        snapshots.append(snapshot)
    row = manifest['accounts'][0]
    try:
        post('/documents/call', row['account'], row['payments'][0], command='payment-0',
             channel='input', operation='record', request={'sequence': -1}, timestamp=100)
        raise RuntimeError('Conflicting idempotency payload was accepted')
    except urllib.error.HTTPError as error:
        if error.code != 400:
            raise
    return {'snapshots': snapshots, 'conflictingPayloadRejected': True}


def recover(account):
    await_total(account, 'initializedOrders', 1)
    await_total(account, 'paidTotal', 42)
    state = post('/documents/read', account, expanded=True)
    order = state['data']['orders']['o']
    if order['paidTotal'] != 42 or order['payments']['p0']['recordedAmount'] != 42:
        raise RuntimeError('Recovered graph does not match the committed payment')
    return {'account': account, 'version': state['version'], 'paidTotal': 42,
            'initializedOrders': 1, 'allLevelsChecked': True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', choices=['prepare', 'race', 'verify', 'recover'])
    parser.add_argument('--run', required=True)
    parser.add_argument('--examples', type=Path)
    parser.add_argument('--manifest', type=Path)
    parser.add_argument('--start', type=float)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if not args.run.startswith(('cluster-', 'recovery-')):
        parser.error('Synthetic cluster-* or recovery-* namespace required')
    if args.phase == 'prepare':
        result = prepare(args)
    elif args.phase == 'recover':
        result = recover(args.run)
    else:
        manifest = json.loads(args.manifest.read_text())
        if manifest['run'] != args.run:
            raise ValueError('Manifest belongs to another run')
        result = race(manifest, args.start) if args.phase == 'race' else verify(manifest)
    args.output.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({'phase': args.phase, 'run': args.run, 'status': 'passed'}), flush=True)


if __name__ == '__main__':
    main()
