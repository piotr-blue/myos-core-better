#!/usr/bin/env python3
"""Run bounded load against an exact, exclusively qualified experimental deployment."""
import argparse
import hashlib
import json
import re
import shlex
import uuid
from pathlib import Path

from aws import aws, verify, STACK


def check_deployment(deployment, outputs, status):
    """Reject stale or cross-stack metadata before uploading or launching anything."""
    if any(deployment[key] != outputs[name] for key, name in
           [('instance', 'Instance'), ('table', 'Table'), ('bucket', 'Artifacts')]):
        raise RuntimeError('Deployment metadata belongs to another experiment stack')
    if not re.fullmatch(r'[0-9a-f]{64}', deployment['artifactSha256']):
        raise RuntimeError('Invalid immutable artifact identity')
    if status != 'Success':
        raise RuntimeError('Wait for healthy deployment completion')


def commands(deployment, digest, tool_key, name, args):
    """Pure shell generation, tested without credentials or AWS calls."""
    release = '/opt/myos-better/releases/' + deployment['artifactSha256']
    script = '/var/lib/myos-better/throughput-' + digest + '.py'
    report = '/var/lib/myos-better/' + name + '.json'
    bucket = deployment['bucket']

    def upload(path, key):
        return shlex.join(['aws', 's3', 'cp', path, 's3://' + bucket + '/reports/' + key,
                           '--region', 'eu-west-1', '--only-show-errors'])

    # The running process, configured capacity and exact examples must describe the same release.
    check = '''import json,sys,urllib.request
from pathlib import Path
pid,release,workers,entries,mib,uses,edition=sys.argv[1:]
args=Path('/proc/'+pid+'/cmdline').read_bytes().split(b'\\0')
def require(condition, reason):
    if not condition: raise RuntimeError(reason)
require(any((release+'/lib/').encode() in arg for arg in args), 'Different running release')
def get(path):
    with urllib.request.urlopen('http://127.0.0.1:8080'+path,timeout=10) as response:
        return json.load(response)
health=get('/health'); cache=get('/metrics')['sessions']
require(health['status']=='ok' and health['durable'] is True, 'Non-durable or unhealthy service')
require(health['workers']==int(workers), 'Different worker count')
require(health['edition']==edition, 'Different local protocol edition')
require(cache['maximumEntries']==int(entries), 'Different session entry limit')
require(cache['maximumBytes']==int(mib)*1024*1024, 'Different session byte limit')
require(cache['maximumUses']==int(uses), 'Different session reuse limit')
'''
    sample = '''import json,sys,time
from pathlib import Path
pid,phase,output=sys.argv[1:]
status=dict(line.split(':',1) for line in Path('/proc/'+pid+'/status').read_text().splitlines() if ':' in line)
record={'phase':phase,'time':time.time(),'pid':int(pid)}
for field in ('VmRSS','VmHWM','Threads'):
    record[field]=status[field].strip()
with open(output,'a') as stream: stream.write(json.dumps(record)+'\\n')
'''
    result = [
        'set -euo pipefail',
        'exec 9>/var/lock/myos-better-deploy.lock; flock -n 9',
        'test "$(readlink -f /opt/myos-better/current)" = ' + shlex.quote(release),
        'pid=$(systemctl show --property=MainPID --value myos-better.service); test "$pid" -gt 1',
        'python3 -c ' + shlex.quote(check) + ' "$pid" ' + shlex.join([
            release, str(deployment.get('workers', 2)), str(deployment.get('sessionEntries', 64)),
            str(deployment.get('sessionMiB', 128)), str(deployment.get('sessionUses', 32)),
            deployment.get('edition', 'AYqefXDffJR23qq1exeZN33bNVxT6ZiraXtJg9bk4Vup')]),
        shlex.join(['aws', 's3', 'cp', 's3://' + bucket + '/' + tool_key, script,
                    '--region', 'eu-west-1', '--only-show-errors']),
        'echo ' + shlex.quote(digest + '  ' + script) + ' | sha256sum --check --status',
        'chmod 644 ' + shlex.quote(script),
        'python3 -c ' + shlex.quote(sample) + ' "$pid" before ' + shlex.quote(report + '.process.jsonl'),
    ]
    if args.profile:
        recording = '/var/lib/myos-better/' + name + '.jfr'
        profile = '/var/lib/myos-better/' + name + '.jfc'
        result.extend([
            'sudo -u myos-better jfr configure --input profile.jfc --output ' + shlex.quote(profile)
            + ' ' + shlex.quote('jdk.TLSHandshake#enabled=true'),
            'sudo -u myos-better jcmd "$pid" JFR.configure stackdepth=128',
            'sudo -u myos-better jcmd "$pid" JFR.start name=myos-qualification settings='
            + shlex.quote(profile) + ' duration=180s maxsize=64m filename=' + shlex.quote(recording),
            """trap 'sudo -u myos-better jcmd "$pid" JFR.stop name=myos-qualification >/dev/null 2>&1 || true' EXIT""",
        ])
    result.extend([
        shlex.join(['sudo', '-u', 'myos-better', 'python3', script, '--examples', release + '/examples',
                    '--accounts', str(args.accounts), '--orders', str(args.orders),
                    '--operations', str(args.operations), '--output', report]),
        'test "$(systemctl show --property=MainPID --value myos-better.service)" -eq "$pid"',
        'python3 -c ' + shlex.quote(sample) + ' "$pid" after ' + shlex.quote(report + '.process.jsonl'),
        upload(report, name + '.json'),
        upload(report + '.process.jsonl', name + '.process.jsonl'),
    ])
    if args.profile:
        result.extend([
            'sudo -u myos-better jcmd "$pid" JFR.stop name=myos-qualification',
            'trap - EXIT',
            upload(recording, name + '.jfr'),
        ])
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--accounts', type=int, default=16)
    parser.add_argument('--orders', type=int, default=8)
    parser.add_argument('--operations', type=int, default=32)
    parser.add_argument('--profile', action='store_true',
                        help='Collect bounded JFR; timings include profiling overhead')
    args = parser.parse_args()
    if not (1 <= args.accounts <= 64 and 1 <= args.orders <= 32 and 1 <= args.operations <= 128):
        parser.error('Invalid bounded workload dimensions')
    verify()
    root = Path(__file__).resolve().parents[1]
    outputs = {row['OutputKey']: row['OutputValue'] for row in
               aws('cloudformation', 'describe-stacks', '--stack-name', STACK)['Stacks'][0]['Outputs']}
    deployment = json.loads((root / 'reports/deployment.json').read_text())
    # Validate target scope even before querying the selected command.
    check_deployment(deployment, outputs, 'Success')
    status = aws('ssm', 'get-command-invocation', '--command-id', deployment['commandId'],
                 '--instance-id', outputs['Instance'])
    check_deployment(deployment, outputs, status['Status'])
    source = root / 'scripts/throughput.py'
    digest = hashlib.sha256(source.read_bytes()).hexdigest()
    key = 'releases/tools/throughput-' + digest + '.py'
    aws('s3api', 'put-object', '--bucket', outputs['Artifacts'], '--key', key, '--body', str(source))
    name = (f'throughput-{deployment["artifactSha256"][:12]}-{deployment.get("workers", 2)}w-'
            f'{args.accounts}a-{args.orders}o-{args.operations}ops-{uuid.uuid4().hex[:8]}')
    request = {'DocumentName': 'AWS-RunShellScript', 'InstanceIds': [outputs['Instance']],
               'Parameters': {'commands': commands(deployment, digest, key, name, args),
                              'executionTimeout': ['1800']},
               'Comment': 'Pinned independent accounts with checked durable propagation and memory evidence'}
    reports = root / 'reports'
    reports.mkdir(exist_ok=True)
    path = reports / (name + '-request.json')
    path.write_text(json.dumps(request))
    command = aws('ssm', 'send-command', '--cli-input-json', 'file://' + str(path))['Command']['CommandId']
    metadata = {'commandId': command, 'instance': outputs['Instance'], 'key': 'reports/' + name + '.json',
                'scriptSha256': digest, 'controllerSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                'artifactSha256': deployment['artifactSha256'], 'workers': deployment.get('workers', 2),
                'accounts': args.accounts, 'orders': args.orders, 'operations': args.operations,
                'profiled': args.profile, 'sessionEntries': deployment.get('sessionEntries', 64),
                'sessionMiB': deployment.get('sessionMiB', 128), 'sessionUses': deployment.get('sessionUses', 32),
                'processKey': 'reports/' + name + '.process.jsonl'}
    (reports / (name + '-command.json')).write_text(json.dumps(metadata, indent=2))
    (reports / 'throughput-latest-command.json').write_text(json.dumps(metadata, indent=2))
    print(json.dumps(metadata))


if __name__ == '__main__':
    main()
