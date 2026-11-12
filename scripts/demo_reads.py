#!/usr/bin/env python3
"""Qualify cold workflow reads on a service configured for two child visits and one session use."""
import argparse
import json
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path


SOURCE = """seen: {}
count: 0
children:
  a: {amount: 11}
  b: {amount: 22}
  c: {amount: 33}
contracts:
  input: {type: Contracts/External Channel}
  embedded: {type: Contracts/Process Embedded, collectionPaths: [/children]}
  copy:
    type: Contracts/Sequential Workflow Operation
    channel: input
    steps:
      - type: Contracts/Update Document
        changeset: [{op: replace, path: /count, val: {$add: [{$document: /count}, 1]}}]
      - type: Contracts/Update Document
        changeset: [{op: replace, path: /seen, val: {$document: {$event: /request/path}}}]
"""


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--url', default='http://127.0.0.1:8081')
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    account = 'bench-reads-' + uuid.uuid4().hex
    timings = {}

    def post(path, expected=200, **values):
        body = dict(account=account, document='root', **values)
        request = urllib.request.Request(args.url.rstrip('/') + path,
            data=json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
        start = time.perf_counter()
        try:
            response = urllib.request.urlopen(request, timeout=30)
        except urllib.error.HTTPError as failure:
            response = failure
        with response:
            status = response.status
            result = json.load(response)
        if 'command' in values:
            timings[values['command']] = (time.perf_counter() - start) * 1000
        if status != expected:
            raise RuntimeError((path, status, expected, result))
        return result

    created = post('/documents/create', command='create', source=SOURCE)
    assert created['status'] == 'APPLIED'
    before = post('/documents/head')
    refused = post('/documents/call', expected=413, command='bulk', channel='input',
        operation='copy', request={'path': '/children'}, timestamp=1)
    assert refused['code'] == 'WORKFLOW_READ_LIMIT_EXCEEDED', refused
    assert before == post('/documents/head')
    scalar = post('/documents/call', command='scalar', channel='input', operation='copy',
        request={'path': '/children/a/amount'}, timestamp=2)
    assert scalar['status'] == 'APPLIED', scalar
    assert post('/documents/read', expanded=False)['data']['seen'] == 11
    copied = post('/documents/call', command='child', channel='input', operation='copy',
        request={'path': '/children/a'}, timestamp=3)
    assert copied['status'] == 'APPLIED', copied
    # A second mutation forces restoration of the first copied checkpoint when session uses is one.
    copied_again = post('/documents/call', command='child-again', channel='input', operation='copy',
        request={'path': '/children/b'}, timestamp=4)
    assert copied_again['status'] == 'APPLIED', copied_again
    state = post('/documents/read', expanded=False)
    assert state['data']['seen']['amount'] == 22
    assert 'document' in state['data']['seen']['contracts']['initialized']
    assert state['data']['count'] == 3
    report = dict(account=account, checks='passed', created=created, refusal=refused,
        scalar=scalar, copied=copied, copiedAgain=copied_again, finalVersion=state['version'],
        count=state['data']['count'], copiedAmount=state['data']['seen']['amount'], timingsMillis=timings)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(dict(account=account, checks='passed', timingsMillis=timings)))


if __name__ == '__main__':
    main()
