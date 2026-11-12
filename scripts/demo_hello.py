#!/usr/bin/env python3
"""Exercise the pinned Hello/Activation package example through the private HTTP API."""
import argparse, json, time, uuid, urllib.request
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--url', default='http://127.0.0.1:8080')
parser.add_argument('--account', default='hello-' + str(uuid.uuid4()))
parser.add_argument('--output', type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
steps = []

def post(path, **body):
    request = urllib.request.Request(args.url.rstrip('/') + path,
        data=json.dumps({'account': args.account, 'document': 'hello', **body}).encode(),
        headers={'Content-Type': 'application/json'}, method='POST')
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)

def state():
    return post('/documents/read', expanded=False)

def execute(command, operation, request, timestamp, expected='APPLIED'):
    before = state()
    event = {'source': 'owner', 'operation': {'type': 'Contracts/Operation Request',
        'channel': 'owner', 'operation': operation, 'request': request,
        'document': {'blueId': before['localBlueId']}, 'requireExactDocumentVersion': True}}
    started = time.perf_counter()
    result = post('/documents/process', command=command, event=event, timestamp=timestamp)
    elapsed = (time.perf_counter() - started) * 1000
    assert result['status'] == expected, result
    after = state()
    steps.append({'command': command, 'event': event, 'result': result, 'elapsedMillis': elapsed,
                  'after': after})
    return after

created = post('/documents/create', command='create', source=(root / 'examples/hello/hello.blue').read_text())
assert created['status'] == 'APPLIED', created
initialized = state()
assert initialized['data']['activation']['active'] is False
approved = execute('approve', 'approve_owner', {'revision': 1}, 1)
assert approved['data']['activation']['active'] is True
hello = execute('hello', 'sayHello', {'text': 'Hello from durable MyOS', 'approved': True}, 2)
assert hello['data']['message'] == hello['data']['audit'] == 'Hello from durable MyOS'
rejected = execute('reject', 'sayHello', {'text': 'Rejected', 'approved': False}, 3, 'FAILED')
assert rejected['data']['message'] == 'Hello from durable MyOS'
assert rejected['version'] == hello['version']
receipt = post('/documents/command', command='approve')
assert receipt['inputAvailable'] is True
report = {'account': args.account, 'created': created, 'initialized': initialized, 'steps': steps,
          'approvalReceipt': receipt, 'events': post('/documents/events', after=0, limit=100)}
output = args.output or root / 'reports' / (args.account + '.json')
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps({'account': args.account, 'status': 'PASSED', 'report': str(output),
                 'stepMillis': {step['command']: step['elapsedMillis'] for step in steps}}, indent=2))
