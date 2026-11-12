#!/usr/bin/env python3
"""Restart the verified preview, then time one existing private Payment and capture owner phases.

Requires the QA account file created by qualify_owner_preview.py. No credentials are exported.
"""
import argparse
import json
from pathlib import Path
import time
import urllib.parse
import uuid
from aws import aws, verify
from qualify_preview import Client, prepared_users, wait_for_entry


def command(instance, commands, destination):
    identifier = aws('ssm', 'send-command', '--document-name', 'AWS-RunShellScript',
                     '--instance-ids', instance, '--parameters', json.dumps({'commands': commands, 'executionTimeout': ['120']}))['Command']['CommandId']
    deadline = time.monotonic() + 150
    time.sleep(2)
    while time.monotonic() < deadline:
        result = aws('ssm', 'get-command-invocation', '--command-id', identifier, '--instance-id', instance)
        if result['Status'] not in ('Pending', 'InProgress', 'Delayed'):
            destination.write_text(json.dumps(result, indent=2)+'\n')
            if result['Status'] != 'Success':
                raise RuntimeError('Restart/profile command failed: '+identifier)
            return result
        time.sleep(2)
    raise TimeoutError('Restart/profile command did not finish')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('prior_report', type=Path)
    args = parser.parse_args()
    verify()
    prior = json.loads(args.prior_report.read_text())
    if not prior['passed']: raise ValueError('A completed HTTPS run is required')
    root = Path(__file__).resolve().parents[1]
    reports = root/'reports'
    outputs = {r['OutputKey']:r['OutputValue'] for r in aws('cloudformation','describe-stacks','--stack-name','myos-better-canonical-20261108')['Stacks'][0]['Outputs']}
    console = {r['OutputKey']:r['OutputValue'] for r in aws('cloudformation','describe-stacks','--stack-name','myos-better-canonical-console-20261108')['Stacks'][0]['Outputs']}
    if prior['url'] != console['Url']: raise ValueError('Preview origin mismatch')
    client = Client(prior['url'])
    client.ok('/api/auth/login', prepared_users(reports, console)[0])
    if client.ok('/api/session')['account'] != prior['account']: raise ValueError('Account mismatch')
    selected = prior['results'][-1]
    instance = outputs['Instance']
    before = selected['calls'][-1]['parentVersion']
    # Login and selection happen before restart so they cannot warm the measured execution.
    restart = command(instance, ['set -euo pipefail', 'systemctl restart myos-better.service',
        'for i in $(seq 1 40); do if curl --fail --silent http://127.0.0.1:8080/health >/dev/null; then break; fi; sleep 1; done',
        'curl --fail --silent http://127.0.0.1:8080/health >/dev/null',
        'readlink -f /opt/myos-better/current',
        'pid=$(systemctl show myos-better.service --property MainPID --value)',
        'runuser -u myos-better -- jcmd "$pid" JFR.start name=owner-audit settings=profile maxsize=32m >/dev/null'], reports/'owner-audit-restart-command.json')
    key = 'audit-restart-'+uuid.uuid4().hex
    intent = {'timeline':selected['timeline'],'channel':'input','operation':'record','request':{}}
    start = time.perf_counter()
    submitted = client.ok('/api/documents/'+selected['payment']+'/operations', intent, key=key)
    submit = (time.perf_counter()-start)*1000
    version = wait_for_entry(client, selected['agreement'], submitted['entry'], before)
    elapsed = (time.perf_counter()-start)*1000
    history = client.ok(f"/api/documents/{selected['agreement']}/history/{version}")
    assert history['input']['mode']=='owner-local'
    expected = 42*(len(selected['calls'])+1)
    for owner, path in ((selected['payment'],'/recordedAmount'), (selected['order'],'/paidTotal'), (selected['agreement'],'/paidTotal')):
        actual = client.ok('/api/documents/'+owner+'/value?'+urllib.parse.urlencode({'path':path}))['value']
        assert actual==expected, (owner,actual,expected)
    assert client.ok('/api/documents/'+selected['payment']+'/operations', intent, key=key)==submitted
    profile = command(instance, ['set -euo pipefail', 'pid=$(systemctl show myos-better.service --property MainPID --value)',
        'runuser -u myos-better -- jcmd "$pid" JFR.dump name=owner-audit filename=/var/lib/myos-better/owner-audit.jfr >/dev/null',
        'runuser -u myos-better -- jcmd "$pid" JFR.stop name=owner-audit >/dev/null',
        'jfr print --json --events myos.OwnerExecution /var/lib/myos-better/owner-audit.jfr',
        'rm /var/lib/myos-better/owner-audit.jfr'], reports/'owner-audit-profile-command.json')
    events = json.loads(profile['StandardOutputContent'])['recording']['events']
    selected_events = [event['values'] for event in events if event['values'].get('entry') == submitted['entry']]
    # Old captures may repeat an identical exported chunk after dump + stop to the same file.
    # Preserve distinct execution timestamps; collapse only byte-identical event records.
    phases = list({json.dumps(event, sort_keys=True): event for event in selected_events}.values())
    assert len(phases) == 3 and len({event['owner'] for event in phases}) == 3
    assert all(event['status'] == 'APPLIED' for event in phases)
    result = {'passed':True, 'orders':selected['orders'],'agreement':selected['agreement'], 'entry':submitted['entry'],
              'beforeVersion':before,'afterVersion':version,'expectedTotal':expected,
              'submitMillis':submit,'parentObservedMillis':elapsed,'result':history['outcome'],
              'restartCommand':restart['CommandId'],'profileCommand':profile['CommandId'],'ownerPhases':phases, 'profileOccurrenceCount':len(selected_events),
              'duplicateExportCount':len(selected_events)-len(phases),
              'boundary':'First existing-graph operation after a whole-service restart; JFR profiling enabled; network and client polling included'}
    (reports/'owner-audit-restart.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result))

if __name__=='__main__':main()
