#!/usr/bin/env python3
"""Qualify bounded snapshot pagination while one synthetic child changes."""
import argparse
import json
import re
import shlex
import uuid
from pathlib import Path

from aws import aws, verify, STACK

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--account', required=True)
args = parser.parse_args()
if not re.fullmatch(r'bench-[A-Za-z0-9._-]{1,90}', args.account):
    parser.error('A synthetic bench-* account is required')
verify()
root = Path(__file__).resolve().parents[1]
reports = root / 'reports'
deployment = json.loads((reports / 'deployment.json').read_text())
outputs = {entry['OutputKey']: entry['OutputValue'] for entry in
           aws('cloudformation', 'describe-stacks', '--stack-name', STACK)['Stacks'][0]['Outputs']}
if deployment['instance'] != outputs['Instance']:
    raise RuntimeError('Deployment targets another experiment')
name = deployment['artifactSha256'][:12] + '-read-pages-' + uuid.uuid4().hex[:8]
report = '/var/lib/myos-better/' + name + '.json'
program = r'''import json, statistics, time, urllib.request, urllib.error, uuid
account = ACCOUNT
report = REPORT

def post(endpoint, document='agreement', **values):
    body = dict(account=account, document=document, **values)
    request = urllib.request.Request('http://127.0.0.1:8080' + endpoint,
        data=json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)

before = post('/documents/read', expanded=False)
expected = before['data']['initializedOrders']
if expected < 1:
    raise RuntimeError('A populated synthetic agreement is required')
expanded_rejection = None
if expected > 256:
    started = time.monotonic()
    try:
        post('/documents/read', expanded=True)
        raise RuntimeError('Large expanded view did not respect the host limit')
    except urllib.error.HTTPError as error:
        if error.code != 413 or json.load(error).get('code') != 'VIEW_LIMIT_EXCEEDED':
            raise
    expanded_rejection = (time.monotonic() - started) * 1000
    assert post('/documents/head')['version'] == before['version']
started = time.monotonic()
page = post('/documents/children', limit=100)
latencies = [(time.monotonic() - started) * 1000]
version = page['version']
first = page['items'][0]
old_order = post('/documents/read', document=first['document'], version=first['version'], expanded=False)
payment = post('/documents/embedded', path=first['path'] + '/payments/p0')['document']
command = 'page-check-' + str(uuid.uuid4())
result = post('/documents/call', document=payment, command=command, channel='input',
    operation='record', request={}, timestamp=1)
assert result['status'] == 'APPLIED'
deadline = time.monotonic() + 30
while True:
    current = post('/documents/read', expanded=False)
    if current['data']['paidTotal'] == before['data']['paidTotal'] + 42:
        break
    if time.monotonic() >= deadline:
        raise RuntimeError('Payment propagation did not complete')
    time.sleep(.02)
assert current['version'] > version
paths = set()
pages = 0
while True:
    assert page['version'] == version
    assert 0 < len(page['items']) <= 100
    for item in page['items']:
        assert item['path'] not in paths
        paths.add(item['path'])
    pages += 1
    cursor = page['nextCursor']
    if cursor is None:
        break
    started = time.monotonic()
    page = post('/documents/children', cursor=cursor, limit=100)
    latencies.append((time.monotonic() - started) * 1000)
assert len(paths) == expected
historical = post('/documents/read', version=version, expanded=False)
assert historical['data']['paidTotal'] == before['data']['paidTotal']
old_again = post('/documents/read', document=first['document'], version=first['version'], expanded=False)
assert old_again == old_order
new_order = post('/documents/read', document=first['document'], expanded=False)
assert new_order['data']['paidTotal'] == old_order['data']['paidTotal'] + 42
result = dict(account=account, status='PASSED', directChildren=len(paths), pages=pages,
    pinnedParentVersion=version, changedParentVersion=current['version'], pageSize=100,
    pageMedianMillis=statistics.median(latencies), pageP95Millis=sorted(latencies)[int(len(latencies)*.95)],
    pageMillis=latencies, historicalParentAndChildStable=True, expandedViewRejectedMillis=expanded_rejection)
with open(report, 'w') as output:
    json.dump(result, output, indent=2)
print(json.dumps({key:value for key,value in result.items() if key != 'pageMillis'}))
'''.replace('ACCOUNT', repr(args.account)).replace('REPORT', repr(report))
commands = [
    'set -euo pipefail',
    'exec 9>/var/lock/myos-better-deploy.lock; flock -n 9',
    'test "$(readlink -f /opt/myos-better/current)" = ' + shlex.quote('/opt/myos-better/releases/' + deployment['artifactSha256']),
    "sudo -u myos-better python3 - <<'CHECK_PAGES'\n" + program + '\nCHECK_PAGES',
    shlex.join(['aws', 's3', 'cp', report, 's3://' + outputs['Artifacts'] + '/reports/' + name + '.json',
                '--region', 'eu-west-1', '--only-show-errors']),
]
request = {'DocumentName': 'AWS-RunShellScript', 'InstanceIds': [outputs['Instance']],
           'Parameters': {'commands': commands, 'executionTimeout': ['300']},
           'Comment': 'Bounded immutable child pagination while a synthetic payment changes'}
request_file = reports / (name + '-request.json')
request_file.write_text(json.dumps(request))
command = aws('ssm', 'send-command', '--cli-input-json', 'file://' + str(request_file))['Command']['CommandId']
metadata = {'commandId': command, 'instance': outputs['Instance'],
            'artifactSha256': deployment['artifactSha256'], 'key': 'reports/' + name + '.json'}
(reports / (name + '-command.json')).write_text(json.dumps(metadata, indent=2))
(reports / 'read-pages-latest-command.json').write_text(json.dumps(metadata, indent=2))
print(json.dumps(metadata))
