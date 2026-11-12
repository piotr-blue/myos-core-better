#!/usr/bin/env python3
"""Authenticated HTTPS qualification of the isolated canonical console and published clauses.

Creates two suppressed-email QA users in the existing sign-in pool. Passwords stay in a local
0600 report file; neither passwords, bearer keys nor cookies enter the public result.
"""
import argparse
import http.cookiejar
import http.client
import json
import os
from pathlib import Path
import secrets
import tempfile
import textwrap
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

from aws import aws, verify
from preview_infra import CONSOLE, outputs


class Client:
    def __init__(self, origin, token=None):
        self.origin = origin
        self.token = token
        self.cookies = http.cookiejar.CookieJar()
        parsed = urllib.parse.urlsplit(origin)
        if parsed.scheme != 'https' or not parsed.hostname or parsed.path:
            raise ValueError('Use an HTTPS origin without a path')
        self.connection = http.client.HTTPSConnection(parsed.hostname, parsed.port, timeout=90)
        self.server_timing = '' 

    def request(self, path, data=None, *, method=None, key=None, origin=None):
        headers = {'Origin': origin or self.origin}
        if data is not None:
            headers['Content-Type'] = 'application/json'
        if key:
            headers['Idempotency-Key'] = key
        if self.token:
            headers['Authorization'] = 'Bearer ' + self.token
        request = urllib.request.Request(self.origin + path,
            data=None if data is None else json.dumps(data).encode(), headers=headers, method=method)
        self.cookies.add_cookie_header(request)
        self.connection.request(request.get_method(), path, body=request.data,
                                headers=dict(request.header_items()))
        response = self.connection.getresponse()
        self.cookies.extract_cookies(response, request)
        self.server_timing = response.getheader('Server-Timing', '')
        raw = response.read()
        try:
            body = json.loads(raw) if raw else {}
        except ValueError:
            body = {'code': 'NON_JSON_ERROR'}
        return response.status, body

    def ok(self, path, data=None, **options):
        status, body = self.request(path, data, **options)
        if status not in (200, 201, 202, 204):
            raise RuntimeError(f'HTTP {status} for {path}: {body.get("code", "unknown")}')
        return body


def prepared_users(reports, selected):
    path = reports / 'canonical-preview-qa-private.json'
    if path.exists():
        saved = json.loads(path.read_text())
        if saved['pool'] != selected['UserPool'] or saved['url'] != selected['Url']:
            raise RuntimeError('QA identities belong to another deployment')
        return saved['users']
    users = []
    for label in ('alice', 'bob'):
        email = 'canonical-' + label + '-' + uuid.uuid4().hex[:12] + '@example.invalid'
        password = secrets.token_urlsafe(24) + 'aA1!'
        for action, payload in (
            ('admin-create-user', {'UserPoolId': selected['UserPool'], 'Username': email,
             'MessageAction': 'SUPPRESS', 'UserAttributes': [
                 {'Name': 'email', 'Value': email}, {'Name': 'email_verified', 'Value': 'true'}]}),
            ('admin-set-user-password', {'UserPoolId': selected['UserPool'], 'Username': email,
             'Password': password, 'Permanent': True})):
            descriptor, temporary = tempfile.mkstemp(prefix='private-cognito-', dir=reports)
            try:
                with os.fdopen(descriptor, 'w') as stream:
                    json.dump(payload, stream)
                aws('cognito-idp', action, '--cli-input-json', 'file://' + temporary)
            finally:
                Path(temporary).unlink(missing_ok=True)
        users.append({'email': email, 'password': password})
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'w') as stream:
        json.dump({'pool': selected['UserPool'], 'url': selected['Url'], 'users': users}, stream)
    return users


def wait_for(predicate, description):
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        result = predicate()
        if result:
            return result
        time.sleep(.1)
    raise TimeoutError('Timed out: ' + description)


def wait_for_entry(client, document, entry, after_version):
    """Observe this input's committed parent result, not another input or child visibility."""
    checked = after_version

    def committed():
        nonlocal checked
        head = client.ok('/api/documents/' + document + '/head')['version']
        for version in range(checked + 1, head + 1):
            history = client.ok(f'/api/documents/{document}/history/{version}')
            if history['input'].get('entry') == entry:
                if history['outcome']['status'] != 'APPLIED':
                    raise AssertionError(history['outcome'])
                return version
        checked = head
        return None

    return wait_for(committed, 'committed parent receipt for submitted entry')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--orders', type=int, default=2)
    args = parser.parse_args()
    if not 1 <= args.orders <= 10:
        parser.error('Use 1..10 bounded published-clause orders')
    verify()
    selected = outputs(CONSOLE)
    root = Path(__file__).resolve().parents[1]
    reports = root / 'reports'
    reports.mkdir(exist_ok=True)
    users = prepared_users(reports, selected)
    clients = []
    for user in users:
        client = Client(selected['Url'])
        client.ok('/api/auth/login', user)
        clients.append(client)
    alice, bob = clients
    account = alice.ok('/api/session')['account']
    alice.ok('/api/blue/v1/me')
    bob.ok('/api/blue/v1/me')
    assert Client(selected['Url']).request('/api/documents')[0] == 401
    assert alice.request('/api/documents', {'source': 'count: 0'}, key='cross-origin', origin='https://invalid.example')[0] == 403
    report = {'url': selected['Url'], 'account': account, 'checks': ['Cognito login', 'anonymous rejection', 'cross-origin write rejection'],
              'startedAt': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
              'measurementBoundary': 'HTTPS client submission through the Agreement committed APPLIED history for this exact submitted entry, including client network and polling with persistent TLS; catalogue and initial-head lookup reported separately'}
    prefix = 'https-qa-' + uuid.uuid4().hex[:12]
    source = (root / 'examples/clause-tutorial/bootstrap-core.blue').read_text()
    document = textwrap.dedent(source.split('document: |\n', 1)[1])
    payload = {'inputMode': 'source', 'document': document, 'participantMappings': [
        {'channelName': name, 'accountId': account} for name in ('customer', 'seller')]}
    start = time.perf_counter()
    admission = alice.ok('/api/blue/v1/documents/bootstrap', payload, key=prefix)
    if 'details' not in admission:
        admission = alice.ok('/api/blue/v1/admissions/' + admission['admissionId'])
    agreement = admission['details']['sessionId']
    report.update(agreement=agreement, bootstrapMillis=(time.perf_counter() - start) * 1000)
    assert alice.ok('/api/blue/v1/documents/bootstrap', payload, key=prefix) == admission
    assert bob.request('/api/documents/' + agreement)[0] == 404
    initial = alice.ok('/api/documents/' + agreement + '/head')
    report['initialHead'] = initial
    initial_history = alice.ok('/api/documents/' + agreement + '/history/1')

    def child(parent, path):
        children = alice.ok('/api/documents/' + parent + '/children')['items']
        return next((row['document'] for row in children if row['path'] == path), None)

    def value(owner, path):
        return alice.ok('/api/documents/' + owner + '/value?' + urllib.parse.urlencode({'path': path}))['value']

    measurements = []
    used_timelines = set()
    destination = reports / ('preview-http-' + prefix + '.json')
    report.update(passed=False, measurements=measurements)
    destination.write_text(json.dumps(report, indent=2) + '\n')
    def call(owner, channel, operation, request, condition):
        lookup_started = time.perf_counter()
        catalogue = alice.ok('/api/documents/' + owner + '/operations')
        before = alice.ok('/api/documents/' + agreement + '/head')['version']
        lookup_millis = (time.perf_counter() - lookup_started) * 1000
        timeline = next(row['timeline'] for row in catalogue['channels'] if channel in row['selectors'])
        used_timelines.add(timeline)
        intent = {'timeline': timeline, 'channel': channel, 'operation': operation, 'request': request}
        command = prefix + '-' + str(len(measurements))
        start = time.perf_counter()
        submitted = alice.ok('/api/documents/' + owner + '/operations', intent, key=command)
        submit = (time.perf_counter() - start) * 1000
        server_timing = alice.server_timing
        assert submitted['status'] == 'SUBMITTED'
        wait_for(condition, operation)
        business_observed = (time.perf_counter() - start) * 1000
        parent_version = wait_for_entry(alice, agreement, submitted['entry'], before)
        elapsed = (time.perf_counter() - start) * 1000
        measurements.append({'operation': operation, 'document': owner, 'submitMillis': submit, 'parentObservedMillis': elapsed,
                             'businessStateObservedMillis': business_observed, 'entry': submitted['entry'], 'parentVersion': parent_version,
                             'catalogueMillis': lookup_millis, 'observedWithCatalogueMillis': elapsed + lookup_millis,
                             'submitServerTiming': server_timing})
        destination.write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps({'phase': 'operation', **measurements[-1]}), flush=True)
        # Retry validation is outside the measured interval.
        assert submitted == alice.ok('/api/documents/' + owner + '/operations', intent, key=command)

    created = []
    for number in range(1, args.orders + 1):
        key = 'order-' + str(number)
        call(agreement, 'customer', 'placeOrder', {'orderId': key}, lambda: child(agreement, '/orders/' + key))
        order = child(agreement, '/orders/' + key)
        call(order, 'buyer', 'preparePayment', {}, lambda: child(order, '/payment'))
        payment = child(order, '/payment')
        call(payment, 'guarantor', 'acceptPaymentInstruction', {}, lambda: value(payment, '/payment/accepted') is True)
        transfer = {'requestId': 'transfer-' + str(number), 'reference': 'bank-demo-' + str(number)}
        call(payment, 'payer', 'reportTransfer', transfer, lambda: value(payment, '/reportedTransfer') is not None)
        call(payment, 'guarantor', 'acknowledgeTransfer', transfer, lambda: value(agreement, '/paidTotal') == number * 200)
        assert value(order, '/paidTotal') == 200
        assert value(payment, '/payment/completed') == 200
        created.append({'order': order, 'payment': payment})
    timelines = alice.ok('/api/timelines')['items']
    assert len(used_timelines) == 2, 'The child documents must reuse the two participant timelines'
    assert alice.ok('/api/documents/' + agreement + '/history/1') == initial_history
    final_head = alice.ok('/api/documents/' + agreement + '/head')
    assert final_head['version'] > initial['version']
    final_history = alice.ok('/api/documents/' + agreement + '/history/' + str(final_head['version']))
    assert final_history['outcome']['status'] == 'APPLIED'
    report.update(finalHead=final_head, finalHistory=final_history)
    events = alice.ok('/api/documents/' + agreement + '/events')
    assert events['items'], 'Agreement reactions must emit retained events'
    report['checks'] += ['bootstrap retry', 'cross-account isolation', 'two shared participant timelines', 'Payment to Order to Agreement propagation', 'operation retry', 'retained events', 'immutable initialization history after later operations', 'latest applied history']
    key = alice.ok('/api/blue/v1/api-keys', {'name': 'bounded canonical qualification'}, key=prefix + '-key')
    secret = key['secret']
    try:
        keyed = Client(selected['Url'], secret)
        assert keyed.ok('/api/session')['account'] == account
        keyed.ok('/api/documents/' + agreement + '/head')
    finally:
        alice.ok('/api/blue/v1/api-keys/' + key['apiKey']['id'], {}, method='DELETE', key=prefix + '-revoke')
    assert Client(selected['Url'], secret).request('/api/documents')[0] == 401
    report['checks'].append('API key creation, authenticated read and revocation')
    report.update(passed=True, orders=created, paidTotal=args.orders * 200, measurements=measurements)
    destination = reports / ('preview-http-' + prefix + '.json')
    destination.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'passed': True, 'report': str(destination), 'url': selected['Url'], 'agreement': agreement, 'paidTotal': report['paidTotal']}))


if __name__ == '__main__':
    main()
