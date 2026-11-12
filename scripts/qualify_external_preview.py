#!/usr/bin/env python3
"""Verify external Timeline import through the canonical preview's authenticated API.

The remote provider is read only. This creates a reader key and import in the isolated
QA account; it does not append entries or alter the external provider's deployment.
"""
import argparse
import json
from pathlib import Path
import time
import uuid

from aws import verify
from preview_infra import CONSOLE, outputs
from qualify_preview import Client, prepared_users, wait_for


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--endpoint', required=True)
    parser.add_argument('--timeline', required=True)
    args = parser.parse_args()
    verify()
    selected = outputs(CONSOLE)
    reports = Path(__file__).resolve().parents[1] / 'reports'
    users = prepared_users(reports, selected)
    client = Client(selected['Url'])
    client.ok('/api/auth/login', users[0])
    command = 'external-qa-' + uuid.uuid4().hex[:12]
    path = '/api/blue/v1/timelines/' + args.timeline
    status, existing = client.request(path)
    reused = status == 200
    if status == 200:
        if existing.get('owned') or existing.get('endpoint') != args.endpoint:
            raise ValueError('Timeline is already registered with another authority')
        reader = {'id': existing['readerKeyId']}
    elif status == 404:
        reader = client.ok('/api/blue/v1/reader-keys',
                           {'name': 'Canonical external import qualification'}, key=command)
    else:
        raise RuntimeError('Cannot inspect existing import: HTTP ' + str(status))
    started = time.perf_counter()
    client.ok('/api/blue/v1/timelines/import', {
        'endpoint': args.endpoint, 'timelineId': args.timeline,
        'readerKeyId': reader['id'], 'name': command}, key=command + '-import')

    def acquired():
        current = client.ok(path)
        return current if current.get('status') == 'AVAILABLE' and current.get('completeBefore', 0) > 0 else None

    before = wait_for(acquired, 'verified external prefix')
    initial_millis = (time.perf_counter() - started) * 1000
    entries = client.ok(path + '/entries?limit=50')
    original_head = before.get('head')
    if original_head and not entries.get('hasMore'):
        assert any(row['blueId'] == original_head for row in entries['items'])
    assert before['owned'] is False
    assert before['timelineId'] == args.timeline
    client.ok(path + '/retry', {}, key=command + '-refresh')
    def advanced():
        current = acquired()
        return current if current and current['completeBefore'] > before['completeBefore'] else None

    after = wait_for(advanced, 'newer verified external prefix after refresh')
    assert after['completeBefore'] > before['completeBefore']
    # Imported access must remain read only even though the importing account owns its key.
    status, _ = client.request(path + '/entries', {'message': {'example': True}}, key=command + '-append')
    assert status == 403, status
    report = {
        'passed': True, 'url': selected['Url'], 'endpoint': args.endpoint,
        'timelineId': args.timeline, 'readerKeyId': reader['id'],
        'importAndAcquireMillis': initial_millis, 'reusedExistingImport': reused,
        'completeBefore': before['completeBefore'],
        'refreshedCompleteBefore': after['completeBefore'], 'initialHead': original_head,
        'refreshedHead': after.get('head'), 'firstPageEntries': len(entries.get('items', [])),
        'checks': ['authenticated import', 'verified prefix acquired', 'strictly newer verified prefix after refresh',
                   'entries readable', 'remote append rejected'],
        'scope': 'Acquisition/API check; document execution parity is qualified separately.'}
    destination = reports / (command + '.json')
    destination.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report), flush=True)
    print('Report: ' + str(destination))


if __name__ == '__main__':
    main()
