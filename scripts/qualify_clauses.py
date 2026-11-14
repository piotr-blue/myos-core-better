#!/usr/bin/env python3
"""Run published Agreement/Order/Payment clauses on a temporary AWS worker."""
import argparse
import hashlib
import json
import re
import shlex
import tarfile
import uuid
from pathlib import Path

from aws import ACCOUNT, REGION, STACK, aws, verify
from qualification_host import worker, wait


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--orders', type=int, default=2)
    parser.add_argument('--historical-clauses', action='store_true',
                        help='Use the retained historical catalogue and IncrementalDocuments fixture')
    parser.add_argument('--private-definitions', action='store_true',
                        help='Disable shared immutable package definitions')
    parser.add_argument('--reader-prefix', default='myos-better-reader-canonical-v1-')
    parser.add_argument('--audit', nargs=2, metavar=('ACCOUNT', 'ROOT'),
                        help='Only replay an existing isolated QA history; submit no operations')
    args = parser.parse_args()
    if not 1 <= args.orders <= 20:
        parser.error('Use 1..20 orders for this bounded real-clause journey')
    if args.audit and not args.audit[0].startswith('clause-qa-'):
        parser.error('Audit only isolated clause-qa accounts')
    if not re.fullmatch(r'myos-better-reader-[a-z0-9-]{1,64}-', args.reader_prefix):
        parser.error('Use a scoped myos-better reader prefix')
    root = Path(__file__).resolve().parents[1]
    reports = root / 'reports' / ('clauses-' + uuid.uuid4().hex[:12])
    reports.mkdir(parents=True)
    install = root / 'build/install/myos-core-better'
    runtime = 'examples/clause-tutorial/' + (
        'runtime.json' if args.historical_clauses else 'runtime-current-clauses.json')
    source = 'examples/' + (
        'clause-payment-reaction' if args.historical_clauses else 'clause-current') + '/agreement.blue'
    archive = reports / 'clause-qualification.tar.gz'
    jars = list((root / 'build/libs').glob('*-qualification.jar'))
    if len(jars) != 1 or not (install / 'lib').is_dir():
        raise RuntimeError('Build installDist qualificationJar first')
    runtime_digest = hashlib.sha256((install / runtime).read_bytes()).hexdigest()
    source_digest = hashlib.sha256((install / source).read_bytes()).hexdigest()
    artifacts = [{
        'name': path.name, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()
    } for path in sorted((install / 'lib').glob('*.jar'))
        if path.name.startswith(('blue-java-better-', 'myos-core-better-'))]
    artifacts.append({'name': jars[0].name,
                      'sha256': hashlib.sha256(jars[0].read_bytes()).hexdigest()})
    verify()
    outputs = {o['OutputKey']: o['OutputValue'] for o in aws(
        'cloudformation', 'describe-stacks', '--stack-name', STACK)['Stacks'][0]['Outputs']}

    def readable(info):
        info.mode = 0o755 if info.isdir() else 0o644
        return info

    with tarfile.open(archive, 'x:gz') as bundle:
        bundle.add(root / 'build/install/myos-core-better/lib', arcname='lib', filter=readable)
        bundle.add(root / 'build/install/myos-core-better/examples', arcname='examples', filter=readable)
        bundle.add(jars[0], arcname='qualification.jar', filter=readable)
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    key = 'releases/clause-qualification-' + digest + '.tgz'
    aws('s3api', 'put-object', '--bucket', outputs['Artifacts'], '--key', key, '--body', str(archive))
    remote = '/tmp/myos-clauses-' + digest
    account = args.audit[0] if args.audit else 'clause-qa-' + uuid.uuid4().hex[:12]
    commands = ['set -euo pipefail', 'umask 022', 'cloud-init status --wait',
                'if systemctl is-active --quiet myos-better || systemctl is-active --quiet myos-native-external; then exit 1; fi',
                shlex.join(['mkdir', '-p', remote]),
                shlex.join(['aws', 's3', 'cp', 's3://' + outputs['Artifacts'] + '/' + key,
                            remote + '/bundle.tgz', '--region', REGION, '--only-show-errors']),
                'echo ' + shlex.quote(digest + '  ' + remote + '/bundle.tgz') + ' | sha256sum --check --status',
                shlex.join(['tar', '-xzf', remote + '/bundle.tgz', '-C', remote]),
                shlex.join(['systemd-run', '--quiet', '--wait', '--pipe', '--collect', '--unit=' + account,
                            '--uid=myos-better', '--property=MemoryMax=1536M', '--property=MemorySwapMax=0',
                            '--property=CPUQuota=200%', '--property=WorkingDirectory=' + remote,
                            '--setenv=AWS_REGION=' + REGION, '--setenv=MYOS_READER_AWS_ACCOUNT=' + ACCOUNT,
                            '--setenv=MYOS_READER_SECRET_PREFIX=' + args.reader_prefix,
                            '--setenv=MYOS_PUBLIC_DEFINITIONS=' + str(not args.private_definitions).lower(),
                            'java', '-Xmx1g', '-XX:+UseG1GC', '-XX:ActiveProcessorCount=2',
                            '-Dblue.probe.clauseRuntime=' + runtime,
                            '-Dblue.probe.clauseSource=' + source,
                            '-Dblue.probe.clauseOrders=' + str(args.orders),
                            '-cp', remote + '/lib/*:' + remote + '/qualification.jar',
                            *(['myos.better.OwnerReplay', outputs['Table'], *args.audit]
                              if args.audit else ['myos.better.ClauseWorkflowProbe', outputs['Table'], account])])]
    with worker(outputs, reports) as identity:
        output_prefix = 'reports/qualification/' + reports.name
        request = {'DocumentName': 'AWS-RunShellScript', 'InstanceIds': [identity],
                   'OutputS3BucketName': outputs['Artifacts'], 'OutputS3KeyPrefix': output_prefix,
                   'Parameters': {'commands': commands, 'executionTimeout': ['900']},
                   'Comment': 'Isolated published-clause signed-input console API qualification'}
        path = reports / 'request.json'
        path.write_text(json.dumps(request))
        command = aws('ssm', 'send-command', '--cli-input-json', 'file://' + str(path))['Command']['CommandId']
        metadata = {'commandId': command, 'instance': identity, 'account': account,
                    'digest': digest, 'orders': args.orders, 'reports': str(reports),
                    'runtimeManifest': runtime, 'runtimeSha256': runtime_digest,
                    'agreementSource': source, 'sourceSha256': source_digest,
                    'publicDefinitions': not args.private_definitions,
                    'profiled': not bool(args.audit), 'garbageCollector': 'G1',
                    'heapGiB': 1, 'cpuQuotaPercent': 200, 'artifacts': artifacts}
        (reports / 'command.json').write_text(json.dumps(metadata, indent=2) + '\n')
        print(json.dumps(metadata), flush=True)
        result = wait(command, identity, reports / 'result.json',
                      output=(outputs['Artifacts'], output_prefix))
        for line in result.get('StandardOutputContent', '').splitlines():
            if line.startswith('{'):
                print(line, flush=True)


if __name__ == '__main__':
    main()
