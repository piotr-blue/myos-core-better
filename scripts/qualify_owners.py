#!/usr/bin/env python3
"""Run independent-owner qualification on an isolated AWS worker.

Validate local inputs and reserve a fresh archive before accessing AWS. Existing
qualification evidence is never reused: select a new --report-dir for another run.
The worker context owns cleanup of instances created by this invocation.
"""

import argparse
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
import shlex
import tarfile
import uuid

from aws import STACK, aws, verify
from qualification_host import wait, worker


@dataclass(frozen=True)
class Resources:
    heap_gib: int
    cpu: int
    memory_limit: str
    execution_timeout: int
    large_graph: bool

    @staticmethod
    def for_counts(counts):
        return (Resources(8, 4, '12G', 5400, True) if max(counts) > 1024
                else Resources(1, 2, '1536M', 600, False))


def order_counts(value):
    """Validate every requested size before selecting resources or credentials."""
    try:
        counts = tuple(int(part) for part in value.split(','))
    except ValueError:
        raise argparse.ArgumentTypeError('Use comma-separated integer Order counts') from None
    if any(count < 1 or count > 10000 for count in counts):
        raise argparse.ArgumentTypeError('Use counts between 1 and 10000')
    if len(set(counts)) != len(counts):
        raise argparse.ArgumentTypeError('Order counts must be distinct')
    return counts


def arguments():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--stack', default=STACK)
    parser.add_argument('--report-dir', type=Path, help='Fresh directory for this run')
    parser.add_argument('--samples', type=int, default=5)
    parser.add_argument('--orders', type=order_counts, default='1,128')
    parser.add_argument('--instance', help='Existing dedicated worker; otherwise create a temporary worker')
    parser.add_argument('--profile', action='store_true', help='Record the final payment per size with JFR')
    parser.add_argument('--public-definitions', action='store_true',
                        help='Use the shared public catalogue, matching MYOS_PUBLIC_DEFINITIONS=true')
    parser.add_argument('--observable-root', action='store_true',
                        help='Emit equal-valued public Agreement events and verify ordered occurrences')
    parser.add_argument('--separate-timelines', action='store_true',
                        help='One timeline per Order; quiet providers certify a prefix beyond active inputs')
    parser.add_argument('--rotate-targets', action='store_true',
                        help='Select a different Order by a deterministic stride on every input')
    parser.add_argument('--direct-creation', action='store_true',
                        help='Test signed, document-addressed creation with a populated source archive')
    parser.add_argument('--cold', action='store_true', help='Reopen storage caches before every input')
    args = parser.parse_args()
    if args.separate_timelines and args.rotate_targets:
        parser.error('Separate timelines reserve quiet prefixes; rotating targets require the shared timeline')
    if not 1 <= args.samples <= 20:
        parser.error('Samples must be 1..20')
    return parser, args


def prepare_reports(parser, directory):
    """Protect prior launcher, host and SSM evidence without requiring an empty directory."""
    reserved = ('owner-qualification.tar.gz', 'owners-qualification-*', 'qualification-host*')
    if any(next(directory.glob(pattern), None) is not None for pattern in reserved):
        parser.error('Qualification evidence already exists; select a new --report-dir')
    directory.mkdir(parents=True, exist_ok=True)


def sha256(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def package(root, reports):
    libraries = root / 'build/install/myos-core-better/lib'
    probes = list((root / 'build/libs').glob('*-qualification.jar'))
    if len(probes) != 1 or not libraries.is_dir():
        raise RuntimeError('Build installDist qualificationJar first')

    def readable(info):
        info.mode = 0o755 if info.isdir() else 0o644
        return info

    archive = reports / 'owner-qualification.tar.gz'
    # Exclusive creation also protects evidence when two invocations race.
    with tarfile.open(archive, 'x:gz') as bundle:
        bundle.add(libraries, arcname='lib', filter=readable)
        bundle.add(probes[0], arcname='qualification.jar', filter=readable)
    artifacts = [probes[0], *[jar for jar in libraries.glob('*.jar')
                             if jar.name.startswith(('blue-java-better-', 'myos-core-better-'))]]
    return archive, sha256(archive), {jar.name: sha256(jar) for jar in artifacts}


def worker_commands(args, resources, outputs, digest, key, account):
    remote = '/tmp/myos-owners-' + digest
    profile = '/tmp/' + account + '-profiles'
    properties = {
        'expectedHeapBytes': resources.heap_gib * 1024**3,
        'directAppend': args.direct_creation,
        'seedSource': args.direct_creation,
        'cold': args.cold,
        'observableRoot': args.observable_root,
        'rotateTargets': args.rotate_targets,
        'separateTimelines': args.separate_timelines,
        'samples': args.samples,
        'orders': ','.join(map(str, args.orders)),
    }
    if args.profile:
        properties['profile'] = profile
    java = ['java', '-XX:+UseG1GC', f'-Xmx{resources.heap_gib}g', f'-XX:ActiveProcessorCount={resources.cpu}']
    java += [f'-Dblue.probe.{name}={str(value).lower() if isinstance(value, bool) else value}'
             for name, value in properties.items()]
    java += ['-cp', remote + '/lib/*:' + remote + '/qualification.jar',
             'myos.better.OwnerScaleProbe', outputs['Table'], account]
    return [
        'set -euo pipefail', 'umask 022', 'cloud-init status --wait',
        'if systemctl is-active --quiet myos-better || systemctl is-active --quiet myos-native-external; '
        'then echo "Refusing to benchmark on a live service host" >&2; exit 1; fi',
        shlex.join(['mkdir', '-p', remote]),
        shlex.join(['aws', 's3', 'cp', 's3://' + outputs['Artifacts'] + '/' + key,
                    remote + '/bundle.tgz', '--region', 'eu-west-1', '--only-show-errors']),
        'echo ' + shlex.quote(digest + '  ' + remote + '/bundle.tgz') + ' | sha256sum --check --status',
        shlex.join(['tar', '-xzf', remote + '/bundle.tgz', '-C', remote]),
        shlex.join(['install', '-d', '-m', '750', '-o', 'myos-better', profile]),
        shlex.join(['systemd-run', '--quiet', '--wait', '--pipe', '--collect', '--unit=' + account,
                    '--uid=myos-better', '--property=MemoryMax=' + resources.memory_limit,
                    '--property=MemorySwapMax=0', f'--property=CPUQuota={resources.cpu * 100}%',
                    '--setenv=AWS_REGION=eu-west-1',
                    '--setenv=MYOS_PUBLIC_DEFINITIONS=' + str(args.public_definitions).lower(), *java]),
    ]


def main():
    parser, args = arguments()
    root = Path(__file__).resolve().parents[1]
    reports = args.report_dir or root / 'reports'
    prepare_reports(parser, reports)
    archive, digest, artifacts = package(root, reports)
    resources = Resources.for_counts(args.orders)

    verify()
    stack = aws('cloudformation', 'describe-stacks', '--stack-name', args.stack)['Stacks'][0]
    outputs = {item['OutputKey']: item['OutputValue'] for item in stack['Outputs']}
    key = 'releases/owners-qualification-' + digest + '.tgz'
    aws('s3api', 'put-object', '--bucket', outputs['Artifacts'], '--key', key, '--body', str(archive))
    account = 'owner-qa-' + uuid.uuid4().hex[:12]
    commands = worker_commands(args, resources, outputs, digest, key, account)
    with worker(outputs, reports, args.instance, large_graph=resources.large_graph) as identity:
        output_prefix = 'reports/qualification/' + account
        request = {
            'DocumentName': 'AWS-RunShellScript', 'InstanceIds': [identity],
            'OutputS3BucketName': outputs['Artifacts'], 'OutputS3KeyPrefix': output_prefix,
            'Parameters': {'commands': commands, 'executionTimeout': [str(resources.execution_timeout)]},
            'Comment': 'Isolated independent owner DynamoDB parity and bounded latency qualification',
        }
        request_file = reports / 'owners-qualification-request.json'
        request_file.write_text(json.dumps(request, indent=2) + '\n')
        command = aws('ssm', 'send-command', '--cli-input-json', 'file://' + str(request_file))['Command']['CommandId']
        result = {
            'commandId': command, 'instance': identity, 'table': outputs['Table'], 'account': account,
            'digest': digest, 'counts': args.orders, 'cold': args.cold, 'profileSummaries': args.profile,
            'gasSchedule': 'STANDARD', 'rotateTargets': args.rotate_targets,
            'separateTimelines': args.separate_timelines, 'observableRoot': args.observable_root,
            'directCreation': args.direct_creation, 'heapGiB': resources.heap_gib, 'cpu': resources.cpu,
            'publicDefinitions': args.public_definitions, 'garbageCollector': 'G1', 'artifacts': artifacts,
        }
        (reports / 'owners-qualification-command.json').write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps(result), flush=True)
        wait(command, identity, reports / 'owners-qualification-current-result.json',
             output=(outputs['Artifacts'], output_prefix), timeout=resources.execution_timeout + 300)


if __name__ == '__main__':
    main()
