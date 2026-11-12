#!/usr/bin/env python3
"""Run the pinned published Hello/Activation example on an isolated temporary service."""
import json
import shlex
import uuid
from pathlib import Path

from aws import aws, verify, STACK


def main():
    verify()
    reports = Path(__file__).resolve().parents[1] / 'reports'
    deployment = json.loads((reports / 'deployment.json').read_text())
    outputs = {row['OutputKey']: row['OutputValue'] for row in
               aws('cloudformation', 'describe-stacks', '--stack-name', STACK)['Stacks'][0]['Outputs']}
    if any(deployment[key] != outputs[name] for key, name in
           [('instance', 'Instance'), ('table', 'Table'), ('bucket', 'Artifacts')]):
        raise RuntimeError('Deployment metadata does not belong to the isolated experiment')
    status = aws('ssm', 'get-command-invocation', '--command-id', deployment['commandId'],
                 '--instance-id', deployment['instance'])
    if status['Status'] != 'Success':
        raise RuntimeError('A healthy deployment is required')
    name = deployment['artifactSha256'][:12] + '-hello-' + uuid.uuid4().hex[:8]
    output = '/var/lib/myos-better/' + name + '.json'
    release = '/opt/myos-better/releases/' + deployment['artifactSha256']
    unit = 'myos-better-hello-qualification'
    commands = [
        'set -euo pipefail', 'exec 9>/var/lock/myos-better-deploy.lock; flock -n 9',
        "trap 'systemctl stop " + unit + ".service || true' EXIT",
        shlex.join(['systemd-run', '--unit=' + unit, '--collect', '--property=User=myos-better',
                    '--property=Group=myos-better', '--property=MemoryMax=1400M', '/usr/bin/env',
                    'AWS_REGION=eu-west-1', 'MYOS_TABLE=' + deployment['table'], 'PORT=8081',
                    'MYOS_RUNTIME=' + release + '/examples/hello/runtime.json',
                    'JAVA_OPTS=-Xms128m -Xmx1g -XX:+ExitOnOutOfMemoryError',
                    release + '/bin/myos-core-better', 'serve']),
        'healthy=0; for i in $(seq 1 45); do if curl --fail --silent http://127.0.0.1:8081/health; '
        'then healthy=1; break; fi; sleep 1; done; test "$healthy" -eq 1',
        shlex.join(['sudo', '-u', 'myos-better', 'python3', release + '/scripts/demo_hello.py',
                    '--url', 'http://127.0.0.1:8081', '--output', output]),
        shlex.join(['aws', 's3', 'cp', output, 's3://' + deployment['bucket'] + '/reports/' + name + '.json',
                    '--region', 'eu-west-1', '--only-show-errors']),
        'systemctl stop ' + unit + '.service', 'trap - EXIT',
        'curl --fail --silent http://127.0.0.1:8080/health',
    ]
    request = {'DocumentName': 'AWS-RunShellScript', 'InstanceIds': [deployment['instance']],
               'Parameters': {'commands': commands, 'executionTimeout': ['300']},
               'Comment': 'Pinned published Hello and Activation with durable state, events, rejection and audit'}
    path = reports / (name + '-request.json')
    path.write_text(json.dumps(request))
    command = aws('ssm', 'send-command', '--cli-input-json', 'file://' + str(path))['Command']['CommandId']
    metadata = {'commandId': command, 'instance': deployment['instance'],
                'artifactSha256': deployment['artifactSha256'], 'key': 'reports/' + name + '.json'}
    (reports / (name + '-command.json')).write_text(json.dumps(metadata, indent=2))
    (reports / 'hello-latest-command.json').write_text(json.dumps(metadata, indent=2))
    print(json.dumps(metadata))


if __name__ == '__main__':
    main()
