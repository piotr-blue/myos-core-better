#!/usr/bin/env python3
"""Run the published clause journey through the authenticated console API.

No AWS credentials are needed. Set MYOS_API_KEY, or provide --email and enter the
password at the prompt. Each run creates its own agreement and two Timelines.
"""
import argparse
import getpass
import http.cookiejar
import http.client
import json
import os
import statistics
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path


class Client:
    def __init__(self, url, api_key=None):
        self.url = url.rstrip('/')
        self.key = api_key
        self.cookies = http.cookiejar.CookieJar()
        parsed = urllib.parse.urlsplit(self.url)
        if parsed.scheme != 'https' or not parsed.hostname or parsed.path:
            raise ValueError('Use an HTTPS origin without a path')
        self.connection = http.client.HTTPSConnection(parsed.hostname, parsed.port, timeout=60)

    def call(self, path, data=None, command=None):
        headers = {'Origin': self.url}
        if self.key:
            headers['Authorization'] = 'Bearer ' + self.key
        if data is not None:
            headers['Content-Type'] = 'application/json'
            headers['Idempotency-Key'] = command or str(uuid.uuid4())
        request = urllib.request.Request(self.url + '/api/' + path,
            data=None if data is None else json.dumps(data).encode(), headers=headers)
        self.cookies.add_cookie_header(request)
        # Reuse TLS exactly as a browser does. Opening a new connection for every poll
        # would measure repeated handshakes rather than document-processing latency.
        self.connection.request(request.get_method(), '/api/' + path,
                                body=request.data, headers=dict(request.header_items()))
        response = self.connection.getresponse()
        self.cookies.extract_cookies(response, request)
        raw = response.read()
        value = json.loads(raw)
        if response.status >= 400:
            raise RuntimeError(f'{response.status} {path}: {value.get("message", value.get("code"))}')
        return value, response.getheader('Server-Timing', '')

    def get(self, path):
        return self.call(path)[0]

    def value(self, document, pointer):
        return self.get('documents/' + document + '/value?path=' + urllib.parse.quote(pointer, safe=''))['value']

    def child(self, document, pointer):
        return self.get('documents/' + document + '/embedding?path=' + urllib.parse.quote(pointer, safe=''))['document']

    def children(self, document):
        items, cursor, version = [], None, None
        while True:
            path = 'documents/' + document + '/children'
            if cursor is not None:
                path += '?cursor=' + urllib.parse.quote(cursor, safe='')
            page = self.get(path)
            if version is not None and page['version'] != version:
                raise AssertionError('Graph changed during child-count verification')
            version = page['version']
            items.extend(page['items'])
            following = page.get('nextCursor')
            if following is None:
                return items
            if following == cursor:
                raise AssertionError('Child pagination did not advance')
            cursor = following

    def operation(self, document, channel, operation, request, root=None):
        lookup_start = time.perf_counter()
        available = self.get('documents/' + document + '/operations')
        timeline = next(c['timeline'] for c in available['channels'] if channel in c['selectors'])
        root = root or document.split('.')[0]
        before = self.get('documents/' + root + '/head')['version']
        start = time.perf_counter()
        lookup_ms = (start - lookup_start) * 1000
        submitted, timing = self.call('documents/' + document + '/operations',
            {'timeline': timeline, 'channel': channel, 'operation': operation, 'request': request})
        submit_ms = (time.perf_counter() - start) * 1000
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            head = self.get('documents/' + root + '/head')
            if head['version'] > before:
                # Control inputs can publish too; identify this original signed entry.
                for version in range(before + 1, head['version'] + 1):
                    result = self.get(f'documents/{root}/history/{version}')
                    if result['input'].get('entry') == submitted['entry']:
                        outcome = result['outcome']
                        if outcome['status'] != 'APPLIED':
                            raise AssertionError(outcome)
                        return {'operation': operation, 'document': document,
                            'entry': submitted['entry'], 'version': version,
                            'restoreAdmissionExecutionMillis': outcome['executionNanos'] / 1e6,
                            'lookupMillis': lookup_ms,
                            'submitMillis': submit_ms, 'submitServerTiming': timing,
                            'serverPerformance': result.get('performance', {}),
                            'observedWithLookupMillis': (time.perf_counter() - lookup_start) * 1000,
                            'observedTotalMillis': (time.perf_counter() - start) * 1000}
                before = head['version']
            time.sleep(.02)
        raise TimeoutError(self.get('documents/' + root + '/processing'))


def run(client, orders, checkpoint=lambda report: None):
    source = client.get('examples')['incrementalClauseTutorial']
    report = {'url': client.url, 'orders': orders, 'calls': []}
    started = time.perf_counter()
    report['bootstrapCommand'] = str(uuid.uuid4())
    checkpoint(report)
    print(json.dumps({'phase': 'bootstrap', 'command': report['bootstrapCommand']}), flush=True)
    created, _ = client.call('bootstrap', {'source': source}, command=report['bootstrapCommand'])
    agreement = created['document']
    if not agreement.startswith('graph-'):
        raise AssertionError('The deployed server has not enabled canonical graphs')
    report.update(agreement=agreement, bootstrapMillis=(time.perf_counter()-started)*1000,
                  agreementUrl=client.url + '/#documents/' + agreement)
    checkpoint(report)
    print(json.dumps({'phase': 'initialized', 'agreement': agreement, 'bootstrapMillis': report['bootstrapMillis']}), flush=True)
    def invoke(doc, channel, operation, request):
        result = client.operation(doc, channel, operation, request, root=agreement)
        report['calls'].append(result)
        checkpoint(report)
        print(json.dumps(result), flush=True)
    # Exercise the complete published Payment workflow at both ends of graph growth.
    paid = 0
    untouched = {}
    samples = {1, orders}
    for n in range(1, orders+1):
        key = f'order-{n}'
        invoke(agreement, 'customer', 'placeOrder', {'orderId': key})
        if n not in samples:
            continue
        order = client.child(agreement, '/orders/' + key)
        invoke(order, 'buyer', 'preparePayment', {})
        payment = client.child(order, '/payment')
        invoke(payment, 'guarantor', 'acceptPaymentInstruction', {})
        receipt = {'requestId': str(uuid.uuid4()), 'reference': 'reproduction-' + key}
        invoke(payment, 'payer', 'reportTransfer', receipt)
        invoke(payment, 'guarantor', 'acknowledgeTransfer', receipt)
        paid += 200
        assert client.value(payment, '/payment/completed') == 200
        assert client.value(order, '/paidTotal') == 200
        assert client.value(agreement, '/paidTotal') == paid
        ids = []
        for doc in (agreement, order, payment):
            channels = client.get('documents/' + doc + '/operations')['channels']
            ids.append({c['timeline'] for c in channels if 'timeline' in c})
        assert len(ids[0]) == 2 and ids[0] == ids[1] == ids[2]
        if n == 1 and orders > 1:
            untouched = {doc: client.get('documents/' + doc + '/head')['localBlueId']
                         for doc in (order, payment)}
        report['lastOrder'] = order
        report['lastPayment'] = payment
    paths = {row['path'] for row in client.children(agreement)
             if row['path'].startswith('/orders/')}
    assert paths == {f'/orders/order-{n}' for n in range(1, orders + 1)}, 'Created Order collection differs'
    report['verifiedOrders'] = len(paths)
    report['paidTotal'] = paid
    if untouched:
        after = {doc: client.get('documents/' + doc + '/head')['localBlueId']
                 for doc in untouched}
        assert untouched == after, 'Unrelated Order or Payment identity changed'
        report['unrelatedIdentities'] = {'before': untouched, 'after': after, 'unchanged': True}
    report['checks'] = ['every requested Order exists in the final collection',
                        'Payment, Order and Agreement totals agree',
                        'all three documents reuse the same two participant Timelines',
                        'every submitted entry has a committed APPLIED outcome']
    report['medianRestoreAdmissionExecutionMillis'] = statistics.median(
        c['restoreAdmissionExecutionMillis'] for c in report['calls'])
    report['timingBoundary'] = ('Legacy API executionNanos includes root restoration, admission and native invocation; '
        'observedTotalMillis ends after the exact submitted entry is found in committed parent history.')
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', default='https://d2buikczdox1rt.cloudfront.net')
    parser.add_argument('--email')
    parser.add_argument('--orders', type=int, default=2)
    parser.add_argument('--report', type=Path, default=Path('canonical-reproduction.json'))
    args = parser.parse_args()
    if not 1 <= args.orders <= 10000:
        parser.error('--orders must be 1..10000')
    client = Client(args.url, os.environ.get('MYOS_API_KEY'))
    if not client.key:
        if not args.email:
            parser.error('Set MYOS_API_KEY or provide --email')
        client.call('auth/login', {'email': args.email, 'password': getpass.getpass('Password: ')})
    def checkpoint(report):
        args.report.parent.mkdir(parents=True, exist_ok=True)
        temporary = args.report.with_suffix(args.report.suffix + '.tmp')
        temporary.write_text(json.dumps(report, indent=2) + '\n')
        temporary.replace(args.report)
    result = run(client, args.orders, checkpoint)
    args.report.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({k:v for k,v in result.items() if k != 'calls'}, indent=2))


if __name__ == '__main__':
    main()
