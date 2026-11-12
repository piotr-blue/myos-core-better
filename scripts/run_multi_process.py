#!/usr/bin/env python3
"""Qualify two isolated service JVMs against the experiment table, then restore normal service."""
import hashlib
import json
import shlex
import uuid
from pathlib import Path

from aws import aws, verify, STACK
import deployment_runtime


def main():
    verify()
    root = Path(__file__).resolve().parents[1]
    reports = root / 'reports'
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
    script = root / 'scripts/multi_process.py'
    digest = hashlib.sha256(script.read_bytes()).hexdigest()
    key = 'releases/tools/multi-process-' + digest + '.py'
    aws('s3api', 'put-object', '--bucket', deployment['bucket'], '--key', key, '--body', str(script))
    name = deployment['artifactSha256'][:12] + '-multi-process-' + uuid.uuid4().hex[:8]
    remote = '/var/lib/myos-better/' + name + '.py'
    output = '/var/lib/myos-better/' + name + '.json'
    release = '/opt/myos-better/releases/' + deployment['artifactSha256']
    units = ['myos-better-qualification-a', 'myos-better-qualification-b']
    profile_name = deployment.get('runtimeProfile', 'canonical')
    profile = deployment_runtime.settings(profile_name)
    if deployment.get('edition', profile['edition']) != profile['edition']:
        raise RuntimeError('Inconsistent deployment edition metadata')
    combined = deployment.get('combinedAcknowledgements', True)
    if not isinstance(combined, bool):
        raise RuntimeError('Invalid acknowledgment policy in deployment metadata')
    def health(port):
        return deployment_runtime.health_check(
            profile_name, port=port, combined_acknowledgements=combined)

    restore = """restore() {
  result=$?
  trap - EXIT
  systemctl stop myos-better-qualification-a.service myos-better-qualification-b.service || true
  systemctl start myos-better.service || result=1
  healthy=0
  for i in $(seq 1 30); do
    if @HEALTH@; then healthy=1; break; fi
    sleep 1
  done
  if [ "$healthy" -ne 1 ]; then result=1; fi
  exit "$result"
}
trap restore EXIT""".replace('@HEALTH@', health(8080))
    commands = ['set -euo pipefail', 'exec 9>/var/lock/myos-better-deploy.lock; flock -n 9',
                'test "$(readlink -f /opt/myos-better/current)" = ' + shlex.quote(release),
                restore,
                shlex.join(['aws', 's3', 'cp', 's3://' + deployment['bucket'] + '/' + key, remote,
                            '--region', 'eu-west-1', '--only-show-errors']),
                'echo ' + shlex.quote(digest + '  ' + remote) + ' | sha256sum --check --status',
                'chmod 644 ' + shlex.quote(remote),
                'systemctl stop myos-better.service']
    for unit, port in zip(units, [8081, 8082]):
        environment = ['MYOS_COMBINE_ACKS=' + str(combined).lower()]
        if profile['manifest'] is not None:
            environment.append('MYOS_RUNTIME=' + release + '/' + profile['manifest'])
        commands += [shlex.join([
            'systemd-run', '--unit=' + unit, '--collect', '--property=User=myos-better',
            '--property=Group=myos-better', '--property=MemoryMax=1200M',
            '/usr/bin/env', 'AWS_REGION=eu-west-1', 'MYOS_TABLE=' + deployment['table'],
            *environment, 'PORT=' + str(port), 'MYOS_WORKERS=4', 'MYOS_SESSION_ENTRIES=64', 'MYOS_SESSION_MIB=128',
            'JAVA_OPTS=-Xms128m -Xmx768m -XX:+ExitOnOutOfMemoryError',
            release + '/bin/myos-core-better', 'serve']),
            'healthy=0; for i in $(seq 1 45); do if ' + health(port)
            + '; then healthy=1; break; fi; sleep 1; done; test "$healthy" -eq 1']
    commands += [shlex.join(['sudo', '-u', 'myos-better', 'python3', remote,
                            '--url', 'http://127.0.0.1:8081', '--url', 'http://127.0.0.1:8082',
                            '--examples', release + '/examples', '--output', output]),
                 shlex.join(['aws', 's3', 'cp', output, 's3://' + deployment['bucket'] + '/reports/' + name + '.json',
                             '--region', 'eu-west-1', '--only-show-errors']),
                 'systemctl stop ' + ' '.join(unit + '.service' for unit in units),
                 'systemctl start myos-better.service',
                 'healthy=0; for i in $(seq 1 30); do if ' + health(8080)
                 + '; then healthy=1; break; fi; sleep 1; done; test "$healthy" -eq 1', 'trap - EXIT']
    request = {'DocumentName': 'AWS-RunShellScript', 'InstanceIds': [deployment['instance']],
               'Parameters': {'commands': commands, 'executionTimeout': ['600']},
               'Comment': 'Two JVMs share experiment DynamoDB; duplicate command races and exact graph views'}
    path = reports / (name + '-request.json')
    path.write_text(json.dumps(request))
    command = aws('ssm', 'send-command', '--cli-input-json', 'file://' + str(path))['Command']['CommandId']
    metadata = {'commandId': command, 'instance': deployment['instance'],
                'artifactSha256': deployment['artifactSha256'], 'scriptSha256': digest,
                'runtimeProfile': profile_name, 'edition': profile['edition'],
                'combinedAcknowledgements': combined,
                'key': 'reports/' + name + '.json'}
    (reports / (name + '-command.json')).write_text(json.dumps(metadata, indent=2))
    (reports / 'multi-process-latest-command.json').write_text(json.dumps(metadata, indent=2))
    print(json.dumps(metadata))


if __name__ == '__main__':
    main()
