#!/usr/bin/env python3
"""Deploy the isolated current-edition HTTP provider; no public mutation endpoints.

First create infra/external-provider-qualification.json for the existing service instance.
This tool uses that stack's URL/security group and the ordinary deployment's instance role.
"""
import argparse
import hashlib
import json
import shlex
import tarfile
from pathlib import Path
from aws import aws, verify, STACK


def outputs(name):
    stack = aws('cloudformation', 'describe-stacks', '--stack-name', name)['Stacks'][0]
    if stack['StackStatus'] not in ('CREATE_COMPLETE', 'UPDATE_COMPLETE'):
        raise RuntimeError('Stack is not ready: ' + stack['StackStatus'])
    return {item['OutputKey']: item['OutputValue'] for item in stack['Outputs']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--external-stack', required=True)
    args = parser.parse_args()
    verify()
    root = Path(__file__).resolve().parents[1]
    reports = root / 'reports'
    reports.mkdir(exist_ok=True)
    host, external = outputs(STACK), outputs(args.external_stack)
    endpoint = external['Url'] + '/api/provider'
    instance = aws('ec2', 'describe-instances', '--instance-ids', host['Instance'])['Reservations'][0]['Instances'][0]
    groups = {group['GroupId'] for group in instance['SecurityGroups']}
    groups.add(external['SecurityGroup'])
    aws('ec2', 'modify-instance-attribute', '--instance-id', host['Instance'], '--groups', *sorted(groups))

    jars = list((root / 'build/libs').glob('*-qualification.jar'))
    if len(jars) != 1:
        raise RuntimeError('Build installDist qualificationJar first')
    archive = root / 'build/external-provider-qualification.tgz'
    def readable(info):
        info.mode = 0o755 if info.isdir() else 0o644
        return info
    with tarfile.open(archive, 'w:gz') as bundle:
        bundle.add(root / 'build/install/myos-core-better/lib', arcname='lib', filter=readable)
        bundle.add(jars[0], arcname='qualification.jar', filter=readable)
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    key = 'releases/external-native-' + digest + '.tgz'
    aws('s3api', 'put-object', '--bucket', host['Artifacts'], '--key', key, '--body', str(archive))
    remote = '/opt/myos-native-external/' + digest
    protected = '/var/lib/myos-native-external/identity.json'
    base = ['java', '-Xmx512m', '-cp', remote + '/lib/*:' + remote + '/qualification.jar',
            'myos.better.ExternalProviderProbe']
    unit = '\n'.join([
        '[Unit]', 'Description=Isolated canonical external Timeline provider',
        'After=network-online.target', '[Service]', 'User=myos-better', 'Group=myos-better',
        'WorkingDirectory=' + remote, 'Environment=AWS_REGION=eu-west-1',
        'ExecStart=' + shlex.join(base + ['serve', host['Table'], endpoint, protected]),
        'Restart=on-failure', 'RestartSec=3', 'NoNewPrivileges=true', 'ProtectSystem=strict',
        'ProtectHome=true', 'PrivateTmp=true', 'ReadWritePaths=/var/lib/myos-native-external',
        'MemoryMax=900M', '[Install]', 'WantedBy=multi-user.target', ''])
    commands = [
        'set -euo pipefail', 'umask 022', shlex.join(['mkdir', '-p', remote]),
        shlex.join(['aws', 's3', 'cp', 's3://' + host['Artifacts'] + '/' + key,
                    remote + '/bundle.tgz', '--region', 'eu-west-1', '--only-show-errors']),
        'echo ' + shlex.quote(digest + '  ' + remote + '/bundle.tgz') + ' | sha256sum --check --status',
        shlex.join(['tar', '-xzf', remote + '/bundle.tgz', '-C', remote]),
        'install -d -m 700 -o myos-better -g myos-better /var/lib/myos-native-external',
        shlex.join(['sudo', '-u', 'myos-better', 'env', 'AWS_REGION=eu-west-1'] + base +
                   ['init', host['Table'], endpoint, protected]),
        "cat > /etc/systemd/system/myos-native-external.service <<'UNIT'\n" + unit + 'UNIT',
        'systemctl daemon-reload', 'systemctl enable myos-native-external',
        'systemctl restart myos-native-external',
        'for n in $(seq 1 30); do if curl --fail --silent http://127.0.0.1:8089/health; then exit 0; fi; sleep 1; done; exit 1']
    request = {'commands': commands, 'executionTimeout': ['180']}
    result = aws('ssm', 'send-command', '--document-name', 'AWS-RunShellScript',
                 '--instance-ids', host['Instance'], '--parameters', json.dumps(request))
    report = {'commandId': result['Command']['CommandId'], 'instance': host['Instance'],
              'endpoint': endpoint, 'table': host['Table'], 'artifactSha256': digest,
              'protectedKeyPath': protected, 'adminCommand': base}
    (reports / 'external-qualification-deployment.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({key: report[key] for key in ('commandId', 'instance', 'endpoint', 'artifactSha256')}))


if __name__ == '__main__':
    main()
