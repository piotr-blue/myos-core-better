#!/usr/bin/env python3
"""Reproduce additive owner-local propagation through the deployed authenticated HTTPS API.

Set MYOS_API_KEY to use your own account. Otherwise create isolated, suppressed-email QA users
using the existing AWS configuration. Only synthetic documents and timelines are created.
"""
import argparse
import json
import os
from pathlib import Path
import time
import urllib.parse
import uuid
from qualify_preview import Client, prepared_users, wait_for_entry


def workflow(channel, steps):
    return {'type': 'Contracts/Sequential Workflow', 'channel': channel, 'steps': steps}


def update(path, expression):
    return {'type': 'Contracts/Update Document', 'changeset': [{'op': 'replace', 'path': path, 'val': expression}]}


def emit(kind, amount):
    return {'type': 'Contracts/Trigger Event', 'event': {'kind': kind, 'amount': amount}}


def source(count, connection):
    orders = {}
    for n in range(count):
        payment = {'name': 'Payment', 'key': 'payment-' + str(n), 'recordedAmount': 0, 'amount': 42,
            'timelineReadAccess': {'profile': 'myos.timeline-access-at-input/1', 'requirements': [connection['readAccess']]},
            'contracts': {'input': connection['channel'], 'record': {
                'type': 'Contracts/Sequential Workflow Operation', 'channel': 'input', 'steps': [
                    update('/recordedAmount', {'$add': [{'$document': '/recordedAmount'}, {'$document': '/amount'}]}),
                    emit('Payment Settled', {'$document': '/amount'})]}}}
        orders['o' + str(n)] = {'name': 'Order', 'number': n, 'paidTotal': 0, 'payments': {'p0': payment},
            'contracts': {
                'embedded': {'type': 'Contracts/Process Embedded', 'collectionPaths': ['/payments']},
                'paid': {'type': 'Contracts/Embedded Node Channel', 'event': {'kind': 'Payment Settled'}},
                'react': workflow('paid', [update('/paidTotal', {'$add': [{'$document': '/paidTotal'}, {'$event': '/event/amount'}]}),
                                         emit('Order Paid', {'$event': '/event/amount'})])}}
    return {'name': 'Owner-local Agreement', 'paidTotal': 0, 'orders': orders, 'contracts': {
        'embedded': {'type': 'Contracts/Process Embedded', 'collectionPaths': ['/orders']},
        'paid': {'type': 'Contracts/Embedded Node Channel', 'event': {'kind': 'Order Paid'}},
        'react': workflow('paid', [update('/paidTotal', {'$add': [{'$document': '/paidTotal'}, {'$event': '/event/amount'}]})])}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', default='https://d2buikczdox1rt.cloudfront.net')
    parser.add_argument('--orders', default='10,100')
    parser.add_argument('--samples', type=int, default=3)
    args = parser.parse_args()
    counts = [int(x) for x in args.orders.split(',')]
    if any(x < 1 or x > 1000 for x in counts) or not 1 <= args.samples <= 20:
        parser.error('Use 1..1000 orders and 1..20 samples')
    reports = Path(__file__).resolve().parents[1] / 'reports'
    reports.mkdir(exist_ok=True)
    client = Client(args.url, os.environ.get('MYOS_API_KEY'))
    if not client.token:
        from aws import aws, verify
        verify()
        stack = aws('cloudformation', 'describe-stacks', '--stack-name', 'myos-better-canonical-console-20261108')['Stacks'][0]
        selected = {x['OutputKey']: x['OutputValue'] for x in stack['Outputs']}
        if selected['Url'] != args.url:
            raise ValueError('QA identities may only be used with their verified deployment')
        client.ok('/api/auth/login', prepared_users(reports, selected)[0])
    account = client.ok('/api/session')['account']
    prefix = 'owner-http-' + uuid.uuid4().hex[:12]
    result = {'url': args.url, 'account': account, 'passed': False, 'results': [],
              'boundary': 'HTTPS operation submission through committed Agreement history for the exact entry; includes client network and polling'}
    destination = reports / (prefix + '.json')
    for count in counts:
        key = prefix + '-' + str(count)
        timeline = client.ok('/api/timelines', {'name': key}, key=key)['timeline']
        connection = client.ok('/api/timelines/' + timeline + '/input', {}, key=key+'-connect')
        payload = {'source': source(count, connection)}
        start = time.perf_counter()
        created = client.ok('/api/documents', payload, key=key+'-create')
        initialized = (time.perf_counter() - start) * 1000
        agreement = created['document']
        assert created == client.ok('/api/documents', payload, key=key+'-create')
        assert created['gasSchedule'] == 'contracts-semantic-gas'
        def child(parent, path):
            rows = client.ok('/api/documents/' + parent + '/children')['items']
            return next(x['document'] for x in rows if x['path'] == path)
        order = child(agreement, '/orders/o0')
        payment = child(order, '/payments/p0')
        row = {'orders': count, 'agreement': agreement, 'order': order, 'payment': payment,
               'timeline': timeline, 'initializationMillis': initialized, 'calls': []}
        result['results'].append(row)
        destination.write_text(json.dumps(result, indent=2)+'\n')
        print(json.dumps({'phase':'initialized', **{k:v for k,v in row.items() if k != 'calls'}}), flush=True)
        for sample in range(args.samples):
            before = client.ok('/api/documents/'+agreement+'/head')['version']
            intent = {'timeline': timeline, 'channel': 'input', 'operation': 'record', 'request': {}}
            start = time.perf_counter()
            submitted = client.ok('/api/documents/'+payment+'/operations', intent, key=key+'-call-'+str(sample))
            submit_millis = (time.perf_counter() - start) * 1000
            version = wait_for_entry(client, agreement, submitted['entry'], before)
            elapsed = (time.perf_counter() - start) * 1000
            history = client.ok(f'/api/documents/{agreement}/history/{version}')
            assert history['input']['mode'] == 'owner-local', history['input'].get('mode')
            for owner, path in ((payment, '/recordedAmount'), (order, '/paidTotal'), (agreement, '/paidTotal')):
                actual = client.ok('/api/documents/'+owner+'/value?' + urllib.parse.urlencode({'path':path}))['value']
                assert actual == 42*(sample+1), (owner, actual)
            assert client.ok('/api/documents/'+payment+'/operations', intent, key=key+'-call-'+str(sample)) == submitted
            call = {'sample':sample, 'submitMillis':submit_millis, 'parentObservedMillis':elapsed,
                    'entry':submitted['entry'], 'parentVersion':version, 'outcome':history['outcome']}
            row['calls'].append(call)
            destination.write_text(json.dumps(result, indent=2)+'\n')
            print(json.dumps({'phase':'operation','orders':count, **call}), flush=True)
    result['passed'] = True
    destination.write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps({'passed': True, 'report':str(destination)}))

if __name__ == '__main__':
    main()
