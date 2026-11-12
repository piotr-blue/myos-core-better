#!/usr/bin/env python3
"""Race identical and distinct commands through two processes sharing one durable store."""
import argparse
import concurrent.futures
import json
import statistics
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', action='append', required=True)
    parser.add_argument('--examples', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if len(args.url) != 2 or args.url[0] == args.url[1]:
        parser.error('Exactly two different process URLs are required')
    run = 'multi-' + uuid.uuid4().hex
    accounts, orders, operations = 4, 2, 16
    source = (args.examples / 'agreement.blue').read_text()
    order_source = (args.examples / 'order.json').read_text()
    payment_names = list(json.loads(order_source)['payments'])

    def post(host, endpoint, account, document='agreement', **values):
        request = urllib.request.Request(
            args.url[host].rstrip('/') + endpoint,
            data=json.dumps({'account': account, 'document': document, **values}).encode(),
            headers={'Content-Type': 'application/json'}, method='POST')
        with urllib.request.urlopen(request, timeout=120) as response:
            return json.load(response)

    def applied(result):
        if result['status'] != 'APPLIED':
            raise RuntimeError('Unexpected business outcome: ' + json.dumps(result))
        return result

    def await_total(account, field, expected):
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            state = post(0, '/documents/read', account)
            if state['data'][field] == expected:
                return state
            if state['data'][field] > expected:
                raise RuntimeError('Duplicate aggregate effects for ' + account)
            time.sleep(.1)
        raise RuntimeError('Propagation did not complete for ' + account)

    prepared = []
    for index in range(accounts):
        account = run + '-' + str(index)
        applied(post(index % 2, '/documents/create', account, command='create', source=source))
        for order in range(orders):
            key = 'o' + str(order)
            definition = json.loads(order_source.replace('o1', key))
            applied(post(order % 2, '/documents/call', account, command='add-' + key,
                         channel='input', operation='addOrder', request={'key': key, 'order': definition},
                         timestamp=order + 1))
        await_total(account, 'initializedOrders', orders)
        payments = [post(0, '/documents/embedded', account,
                         path='/orders/o' + str(order) + '/payments/' + payment)['document']
                    for order in range(orders) for payment in payment_names]
        prepared.append((account, payments))

    def submit(item):
        host, account, document, operation = item
        started = time.perf_counter()
        result = applied(post(host, '/documents/call', account, document,
                              command='payment-' + str(operation), channel='input', operation='record',
                              request={'sequence': operation}, timestamp=operation + 100))
        return (account, operation), result, (time.perf_counter() - started) * 1000

    # Adjacent submissions deliberately contend on the same command/document in different JVMs.
    requests = [(host, account, payments[operation % len(payments)], operation)
                for operation in range(operations) for account, payments in prepared for host in range(2)]
    started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=16) as pool:
        outcomes = list(pool.map(submit, requests))
    grouped, timings = {}, []
    for identity, result, elapsed in outcomes:
        if identity in grouped and grouped[identity] != result:
            raise RuntimeError('Duplicate command did not return the same committed receipt')
        grouped[identity] = result
        timings.append(elapsed)
    snapshots = []
    for account, payments in prepared:
        await_total(account, 'paidTotal', 42 * operations)
        first = post(0, '/documents/read', account, expanded=True)
        second = post(1, '/documents/read', account, version=first['version'], expanded=True)
        if first != second:
            raise RuntimeError('Processes disagree about the same committed graph view')
        for order in first['data']['orders'].values():
            if order['paidTotal'] != 42 * operations // orders:
                raise RuntimeError('Order aggregate mismatch')
            for payment in order['payments'].values():
                if payment['recordedAmount'] != 42 * operations // len(payments):
                    raise RuntimeError('Payment was lost or counted twice')
        snapshots.append({'account': account, 'version': first['version'], 'paidTotal': first['data']['paidTotal']})
    completion = time.perf_counter() - started
    # A changed payload cannot use a committed command ID, even through the other process.
    account, payments = prepared[0]
    try:
        post(1, '/documents/call', account, payments[0], command='payment-0', channel='input',
             operation='record', request={'sequence': -1}, timestamp=100)
        raise RuntimeError('Conflicting idempotency payload was accepted')
    except urllib.error.HTTPError as error:
        if error.code != 400:
            raise
    report = {'run': run, 'processes': 2, 'accounts': accounts,
              'documents': accounts * (1 + orders * (1 + len(payment_names))),
              'uniqueCommands': len(grouped), 'submittedCalls': len(outcomes),
              'completionSeconds': completion, 'callMedianMillis': statistics.median(timings),
              'allDuplicateReceiptsEqual': True, 'allPinnedViewsEqual': True,
              'conflictingPayloadRejected': True, 'roots': snapshots,
              'method': 'Two JVMs, shared DynamoDB; concurrent duplicate and distinct commands; every leaf/order/root checked'}
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
