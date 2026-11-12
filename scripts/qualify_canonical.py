#!/usr/bin/env python3
"""Run bounded inline-equality, DynamoDB and cycle checks on the deployed test instance.

Build with ./gradlew installDist qualificationJar first. Uses the same account-checked
AWS credential selection and isolated stack as deploy.py; uploads code only.
"""
import argparse
import hashlib
import json
import shlex
import time
import uuid
from pathlib import Path
from aws import aws, verify


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--orders', default='1,128,1024')
    args = parser.parse_args()
    counts = [int(value) for value in args.orders.split(',')]
    if not counts or any(value < 1 or value > 10000 for value in counts):
        parser.error('Use positive Order counts up to 10000')
    verify()
    root = Path(__file__).resolve().parents[1]
    deployment = json.loads((root / 'reports/deployment.json').read_text())
    jar, = (root / 'build/libs').glob('*-qualification.jar')
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    key = 'releases/canonical-qualification-' + digest + '.jar'
    aws('s3api', 'put-object', '--bucket', deployment['bucket'], '--key', key, '--body', str(jar))
    account = 'canonical-qa-' + uuid.uuid4().hex[:16]
    remote = '/tmp/myos-canonical-' + digest + '.jar'
    base = ['sudo', '-u', 'myos-better', 'env', 'AWS_REGION=eu-west-1', 'java', '-Xmx1g',
            '-Dblue.probe.orders=' + ','.join(map(str, counts)), '-cp',
            '/opt/myos-better/current/lib/*:' + remote]
    commands = [
        'set -euo pipefail',
        'test "$(basename "$(readlink -f /opt/myos-better/current)")" = ' + shlex.quote(deployment['artifactSha256']),
        shlex.join(['aws', 's3', 'cp', 's3://' + deployment['bucket'] + '/' + key, remote,
                    '--region', 'eu-west-1', '--only-show-errors']),
        'echo ' + shlex.quote(digest + '  ' + remote) + ' | sha256sum --check --status',
        shlex.join(base + ['myos.better.CanonicalScaleProbe', deployment['table'], account]),
        shlex.join(base + ['myos.better.CanonicalCycleProbe', deployment['table'], account]),
    ]
    request = {'DocumentName': 'AWS-RunShellScript', 'InstanceIds': [deployment['instance']],
               'Parameters': {'commands': commands, 'executionTimeout': ['300']},
               'Comment': 'Canonical inline equality, DynamoDB latency and cyclic cold restore'}
    reports = root / 'reports'
    request_file = reports / 'canonical-qualification-request.json'
    request_file.write_text(json.dumps(request))
    command = aws('ssm', 'send-command', '--cli-input-json', 'file://' + str(request_file))['Command']['CommandId']
    identity = {'commandId': command, 'account': account,
                'artifactSha256': deployment['artifactSha256'], 'qualificationSha256': digest}
    (reports / 'canonical-qualification-command.json').write_text(json.dumps(identity, indent=2))
    print(json.dumps(identity), flush=True)
    for _ in range(180):
        time.sleep(2)
        result = aws('ssm', 'get-command-invocation', '--command-id', command,
                     '--instance-id', deployment['instance'])
        if result['Status'] in ('Pending', 'InProgress', 'Delayed'):
            continue
        (reports / 'canonical-qualification-result.json').write_text(json.dumps(result, indent=2))
        print(result.get('StandardOutputContent', ''), flush=True)
        if result['Status'] != 'Success':
            raise RuntimeError(result['Status'] + ': ' + result.get('StandardErrorContent', ''))
        return
    raise TimeoutError('Inspect SSM command ' + command + '; qualification did not finish within the polling bound')


if __name__ == '__main__':
    main()
