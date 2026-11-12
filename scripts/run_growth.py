#!/usr/bin/env python3
"""Measure bounded payment and add-order work against an existing synthetic graph."""
import argparse
import hashlib
import json
import re
import shlex
from pathlib import Path

from aws import aws, verify, STACK
import deployment_runtime


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--account', required=True, help='Existing synthetic bench-* account')
    parser.add_argument('--orders', type=int, required=True, help='Existing o0..oN population eligible for payment selection')
    args = parser.parse_args()
    if not re.fullmatch(r'bench-[A-Za-z0-9._-]{1,90}', args.account):
        parser.error('A synthetic bench-* account is required')
    if not 8 <= args.orders <= 100000:
        parser.error('Order population must be 8..100000')

    verify()
    root = Path(__file__).resolve().parents[1]
    reports = root / 'reports'
    deployment = json.loads((reports / 'deployment.json').read_text())
    outputs = {entry['OutputKey']: entry['OutputValue'] for entry in
               aws('cloudformation', 'describe-stacks', '--stack-name', STACK)['Stacks'][0]['Outputs']}
    if any(deployment[key] != outputs[name] for key, name in
           [('instance', 'Instance'), ('table', 'Table'), ('bucket', 'Artifacts')]):
        raise RuntimeError('Deployment metadata does not belong to this experiment stack')
    status = aws('ssm', 'get-command-invocation', '--command-id', deployment['commandId'],
                 '--instance-id', deployment['instance'])
    if status['Status'] != 'Success':
        raise RuntimeError('A healthy deployment is required')
    probes = list((root / 'build/libs').glob('*-qualification.jar'))
    if len(probes) != 1:
        raise RuntimeError('Run ./gradlew qualificationJar; exactly one tool artifact is required')
    probe = probes[0]
    digest = hashlib.sha256(probe.read_bytes()).hexdigest()
    key = 'releases/tools/qualification-' + digest + '.jar'
    aws('s3api', 'put-object', '--bucket', deployment['bucket'], '--key', key, '--body', str(probe))

    # A unique run name preserves prior evidence even when the same binary is measured again.
    import uuid
    name = deployment['artifactSha256'][:12] + '-growth-' + uuid.uuid4().hex[:8]
    remote = '/var/lib/myos-better/qualification-' + digest + '.jar'
    large = '/var/lib/myos-better/' + name + '-payments.jsonl'
    growth = '/var/lib/myos-better/' + name + '-add-orders.jsonl'
    release = '/opt/myos-better/releases/' + deployment['artifactSha256']
    bucket = deployment['bucket']
    profile_name = deployment.get('runtimeProfile', 'canonical')
    profile = deployment_runtime.settings(profile_name)
    if deployment.get('edition', profile['edition']) != profile['edition']:
        raise RuntimeError('Inconsistent deployment edition metadata')
    environment = ['sudo', '-u', 'myos-better', 'env',
                   'MYOS_TABLE=' + deployment['table'], 'AWS_REGION=eu-west-1']
    if profile['manifest'] is not None:
        environment.append('MYOS_RUNTIME=' + release + '/' + profile['manifest'])
    health = deployment_runtime.health_check(profile_name)
    restore = '''restore() {
  result=$?
  trap - EXIT
  systemctl start myos-better.service || result=1
  healthy=0
  for i in $(seq 1 30); do
    if HEALTH; then healthy=1; break; fi
    sleep 1
  done
  if [ "$healthy" -ne 1 ]; then result=1; fi
  exit "$result"
}
trap restore EXIT'''.replace('HEALTH', health)
    commands = [
        'set -euo pipefail',
        'exec 9>/var/lock/myos-better-deploy.lock; flock -n 9',
        'test "$(readlink -f /opt/myos-better/current)" = ' + shlex.quote(release),
        shlex.join(['aws', 's3', 'cp', 's3://' + bucket + '/' + key, remote,
                    '--region', 'eu-west-1', '--only-show-errors']),
        'echo ' + shlex.quote(digest + '  ' + remote) + ' | sha256sum --check --status',
        'chmod 644 ' + shlex.quote(remote),
        restore,
        'systemctl stop myos-better.service',
        shlex.join(environment + ['JAVA_OPTS=-Xms256m -Xmx1g', release + '/bin/myos-core-better',
                                 'probe', str(args.orders), '3', '128', args.account, '8'])
        + ' > ' + shlex.quote(large) + ' 2>&1',
        shlex.join(['aws', 's3', 'cp', large, 's3://' + bucket + '/reports/' + Path(large).name,
                    '--region', 'eu-west-1', '--only-show-errors']),
        shlex.join(environment + ['/usr/bin/java', '-Xms256m', '-Xmx1g', '-cp',
                                 release + '/lib/*:' + remote, 'myos.better.AddOrderProbe', args.account])
        + ' > ' + shlex.quote(growth) + ' 2>&1',
        shlex.join(['aws', 's3', 'cp', growth, 's3://' + bucket + '/reports/' + Path(growth).name,
                    '--region', 'eu-west-1', '--only-show-errors']),
    ]
    summary = '''import json, statistics
for file in FILES:
    result = json.loads(open(file).read().strip().splitlines()[-1])
    if 'samples' in result:
        print(json.dumps({
            'documentEdition': result['documentEdition'],
            'initialLargeOrders': result['initialLargeOrders'],
            'finalLargeOrders': result['finalLargeOrders'],
            'medians': {label: {field: statistics.median(row[field] for row in rows)
                               for field in ['rootExecutionMillis', 'commitMillis', 'completionMillis']}
                        for label, rows in result['samples'].items()}}))
    else:
        print(json.dumps({key: result[key] for key in [
            'orders', 'documents', 'documentEdition', 'localPaymentMedianMillis', 'leafCommitMedianMillis', 'endToEndMedianMillis']}))
'''.replace('FILES', repr([large, growth]))
    commands.append("python3 - <<'SUMMARY'\n" + summary + '\nSUMMARY')
    request = {'DocumentName': 'AWS-RunShellScript', 'InstanceIds': [deployment['instance']],
               'Parameters': {'commands': commands, 'executionTimeout': ['900']},
               'Comment': 'Bounded payment and interleaved order-growth qualification; service restored on exit'}
    request_file = reports / (name + '-request.json')
    request_file.write_text(json.dumps(request))
    command = aws('ssm', 'send-command', '--cli-input-json', 'file://' + str(request_file))['Command']['CommandId']
    metadata = {'commandId': command, 'instance': deployment['instance'],
                'artifactSha256': deployment['artifactSha256'], 'probeSha256': digest,
                'keys': ['reports/' + Path(path).name for path in [large, growth]],
                'account': args.account, 'eligibleOrders': args.orders, 'addedOrders': 64,
                'creationEdition': profile['edition'], 'runtimeProfile': profile_name}
    (reports / (name + '-command.json')).write_text(json.dumps(metadata, indent=2))
    (reports / 'growth-latest-command.json').write_text(json.dumps(metadata, indent=2))
    print(json.dumps(metadata))


if __name__ == '__main__':
    main()
