#!/usr/bin/env python3
"""Replay two existing public external Timelines, then process a local operation.

Creates only an isolated QA-account document and reader keys/imports. The deployed
external provider is read only. The published example currently contains two amount=7
inputs; unexpected external content fails instead of changing the expected answer.
"""
import argparse
import json
import re
from pathlib import Path
import time
import uuid

from aws import verify
from preview_infra import CONSOLE, outputs
from qualify_preview import Client, prepared_users, wait_for
from reproduce_canonical import Client as DocumentClient


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--command', help='Resume the same bootstrap command after an uncertain reply')
    args = parser.parse_args()
    if args.command and not re.fullmatch(r'mixed-qa-[a-f0-9]{12}', args.command):
        parser.error('Use the mixed-qa command printed by the previous run')
    root = Path(__file__).resolve().parents[1]
    if args.command:
        previous = root / 'reports' / (args.command + '.json')
        if previous.is_file() and 'document' in json.loads(previous.read_text()):
            parser.error('This run already has a document; inspect its report instead of submitting another operation')
    verify()
    selected = outputs(CONSOLE)
    user = prepared_users(root / 'reports', selected)[0]
    client = Client(selected['Url'])
    client.ok('/api/auth/login', user)
    run_id = args.command or 'mixed-qa-' + uuid.uuid4().hex[:12]
    endpoint = 'https://dzkbzs1daaeut.cloudfront.net/api/provider'
    timelines = ['6bd1f9VEkDYyyhAys8wzSK7aRPaJgN5MDXV76whz8WEm',
                 'BVLnBzLP7m8NDbqBJKskwrGLUM7LomjMrp21cxcWuofX']
    imported = []
    for number, timeline in enumerate(timelines):
        path = '/api/blue/v1/timelines/' + timeline
        status, current = client.request(path)
        if status == 404:
            reader = client.ok('/api/blue/v1/reader-keys',
                {'name': run_id + '-' + str(number)}, key=run_id + '-reader-' + str(number))
            client.ok('/api/blue/v1/timelines/import',
                {'endpoint': endpoint, 'timelineId': timeline, 'readerKeyId': reader['id'],
                 'name': run_id + '-' + str(number)}, key=run_id + '-import-' + str(number))
        elif status != 200:
            raise RuntimeError('Cannot inspect import: HTTP ' + str(status))
        def ready():
            current = client.ok(path)
            return current if current.get('status') == 'AVAILABLE' and current.get('completeBefore', 0) > 0 else None
        state = wait_for(ready, 'available imported timeline')
        assert not state['owned'] and state['endpoint'] == endpoint
        entries = client.ok(path + '/entries?limit=50')
        assert not entries['hasMore'] and len(entries['items']) == 1
        entry = entries['items'][0]
        assert entry['entry']['message']['amount']['value'] == 7
        imported.append({'timeline': timeline, 'entry': entry['blueId'],
                         'completeBefore': state['completeBefore']})
    document_client = DocumentClient(selected['Url'])
    document_client.call('auth/login', user)
    start = time.perf_counter()
    report = {'passed': False, 'url': selected['Url'], 'command': run_id,
              'imported': imported, 'resumed': args.command is not None}
    destination = root / 'reports' / (run_id + '.json')
    def save():
        destination.write_text(json.dumps(report, indent=2) + '\n')
    save()
    print(json.dumps({'phase': 'bootstrap', 'command': run_id}), flush=True)
    try:
        created, _ = document_client.call('bootstrap',
            {'source': (root / 'examples/owners/mixed-timelines.blue').read_text()}, command=run_id)
    except Exception as failure:
        report['failure'] = str(failure)
        save()
        raise
    document = created['document']
    report.update(document=document, bootstrapMillis=(time.perf_counter() - start) * 1000)
    save()
    wait_for(lambda: document_client.value(document, '/count') == 14, 'both historical external inputs')
    report['historicalCount'] = 14
    report['bootstrapThroughReplayMillis'] = (time.perf_counter() - start) * 1000
    save()
    report['localOperation'] = document_client.operation(document, 'local', 'increment', {}, root=document)
    assert document_client.value(document, '/count') == 19
    report['finalCount'] = 19
    report['processing'] = document_client.get('documents/' + document + '/processing')
    report['passed'] = True
    report['checks'] = ['two signed historical external entries replayed',
                        'local operation waits for required external completeness',
                        'exact submitted entry has a committed APPLIED outcome',
                        'count is 14 after replay and 19 after local input']
    save()
    print(json.dumps(report), flush=True)
    print('Report: ' + str(destination))


if __name__ == '__main__':
    main()
