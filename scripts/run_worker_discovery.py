#!/usr/bin/env python3
"""Compare exact host artifacts through real workers on fresh documents in the isolated AWS stack."""
import argparse
import hashlib
import json
import re
import shlex
import uuid
from pathlib import Path

from aws import aws, verify, STACK
import deployment_runtime
from run_native_admission import check_deployment


def commands(deployment, artifacts, runtime_files, name, workers, idle_seconds, samples, backlog=0):
    if not re.fullmatch(r'worker-[a-z0-9-]{1,80}', name):
        raise ValueError('Invalid qualification name')
    if not (1 <= workers <= 32 and 2 <= idle_seconds <= 15 and 1 <= samples <= 16 and 0 <= backlog <= 128):
        raise ValueError('Invalid qualification bounds')
    if set(artifacts) != {'baseline', 'candidate', 'tool'}:
        raise ValueError('Three pinned executable artifacts required')
    if any(not re.fullmatch(r'[0-9a-f]{64}', value) for value in artifacts.values()):
        raise ValueError('Invalid artifact digest')
    for filename, digest in runtime_files.items():
        if (not re.fullmatch(r'[A-Za-z0-9._-]+\.jar', filename)
                or filename.startswith('myos-core-better')
                or not re.fullmatch(r'[0-9a-f]{64}', digest)):
            raise ValueError('Common classpath must exclude host artifacts and pin every dependency')
    if not any(filename.startswith('blue-java-better-') for filename in runtime_files):
        raise ValueError('A pinned common Blue library is required')
    release = '/opt/myos-better/releases/' + deployment['artifactSha256']
    bucket = deployment['bucket']
    remote = {role: '/var/lib/myos-better/worker-' + digest + '.jar' for role, digest in artifacts.items()}
    health = deployment_runtime.health_check('canonical', combined_acknowledgements=True)
    result = ['set -euo pipefail', 'exec 9>/var/lock/myos-better-deploy.lock; flock -n 9',
              'test "$(readlink -f /opt/myos-better/current)" = ' + shlex.quote(release),
              'pid=$(systemctl show myos-better.service -p MainPID --value); test "$pid" -gt 0',
              'test "$(readlink -f /proc/$pid/cwd)" = ' + shlex.quote(release), health]
    for role, digest in artifacts.items():
        result.append(shlex.join(['aws', 's3', 'cp', 's3://' + bucket + '/releases/tools/worker-' + digest + '.jar',
                                  remote[role], '--region', 'eu-west-1', '--only-show-errors']))
    lines = [digest + '  ' + remote[role] for role, digest in artifacts.items()]
    lines += [digest + '  ' + release + '/lib/' + filename for filename, digest in runtime_files.items()]
    result += ["printf '%s\\n' " + shlex.quote('\n'.join(lines)) + ' | sha256sum --check --status',
               'chmod 644 ' + shlex.join(sorted(set(remote.values())))]
    restore = '''restore() {
  result=$?
  trap - EXIT
  systemctl start myos-better.service || result=1
  healthy=0
  for i in $(seq 1 30); do
    if HEALTH; then healthy=1; break; fi
    sleep 1
  done
  test "$healthy" -eq 1 || result=1
  exit "$result"
}
trap restore EXIT'''.replace('HEALTH', health)
    result += [restore, 'systemctl stop myos-better.service']
    summary = ('import json,sys; v=json.loads(open(sys.argv[1]).read().splitlines()[-1]); '
               'print(json.dumps({k:v[k] for k in ["status","hostSha256","workers",'
               '"idleWorkReadsPerSecond","localMedianMillis","remoteMedianMillis","finalTotal"]}))')
    for index, variant in enumerate(['baseline', 'candidate', 'candidate', 'baseline']):
        account = name.replace('worker-', 'worker-bench-', 1) + '-' + str(index)
        output = '/var/lib/myos-better/' + name + '-' + str(index) + '.jsonl'
        classpath = ':'.join([remote[variant]] + [release + '/lib/' + filename for filename in sorted(runtime_files)]
                             + [remote['tool']])
        command = shlex.join(['sudo', '-u', 'myos-better', 'env', 'MYOS_TABLE=' + deployment['table'],
            'AWS_REGION=eu-west-1', '/usr/bin/timeout', '--signal=TERM', '--kill-after=15s', '120s',
            '/usr/bin/java', '-Xms128m', '-Xmx768m', '-cp', classpath,
            'myos.better.WorkerDiscoveryProbe', account, str(workers), str(idle_seconds), str(samples), str(backlog)])
        result += ['result=0; ' + command + ' > ' + shlex.quote(output) + ' 2>&1 || result=$?',
                   shlex.join(['aws', 's3', 'cp', output, 's3://' + bucket + '/reports/' + name + '-' + str(index) + '.jsonl',
                               '--region', 'eu-west-1', '--only-show-errors']),
                   'if [ "$result" -ne 0 ]; then tail -n 15 ' + shlex.quote(output) + '; exit "$result"; fi',
                   shlex.join(['python3', '-c', summary, output])]
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline-host', type=Path, required=True)
    parser.add_argument('--workers', type=int, default=8)
    parser.add_argument('--idle-seconds', type=int, default=5)
    parser.add_argument('--samples', type=int, default=8)
    parser.add_argument('--backlog', type=int, default=0, help='Recover this many committed payments on separate orders (0..128)')
    args = parser.parse_args()
    if not (1 <= args.workers <= 32 and 2 <= args.idle_seconds <= 15 and 1 <= args.samples <= 16 and 0 <= args.backlog <= 128):
        parser.error('Invalid bounded qualification dimensions')
    verify()
    root = Path(__file__).resolve().parents[1]
    reports = root / 'reports'
    deployment = json.loads((reports / 'deployment.json').read_text())
    outputs = {row['OutputKey']: row['OutputValue'] for row in
               aws('cloudformation', 'describe-stacks', '--stack-name', STACK)['Stacks'][0]['Outputs']}
    check_deployment(deployment, outputs, 'Success')
    status = aws('ssm', 'get-command-invocation', '--command-id', deployment['commandId'],
                 '--instance-id', deployment['instance'])
    check_deployment(deployment, outputs, status['Status'])
    installed = root / 'build/install/myos-core-better/lib'
    candidates = list(installed.glob('myos-core-better-*.jar'))
    tools = list((root / 'build/libs').glob('*-qualification.jar'))
    if len(candidates) != 1 or len(tools) != 1:
        raise RuntimeError('Build one installed distribution and qualification tool')
    paths = {'baseline': args.baseline_host, 'candidate': candidates[0], 'tool': tools[0]}
    artifacts = {role: hashlib.sha256(path.read_bytes()).hexdigest() for role, path in paths.items()}
    runtime = {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in sorted(installed.glob('*.jar'))
               if not path.name.startswith('myos-core-better')}
    name = 'worker-' + uuid.uuid4().hex
    generated = commands(deployment, artifacts, runtime, name, args.workers, args.idle_seconds, args.samples, args.backlog)
    for role, path in paths.items():
        aws('s3api', 'put-object', '--bucket', deployment['bucket'],
            '--key', 'releases/tools/worker-' + artifacts[role] + '.jar', '--body', str(path))
    request = {'DocumentName': 'AWS-RunShellScript', 'InstanceIds': [deployment['instance']],
               'Parameters': {'commands': generated, 'executionTimeout': ['600']},
               'Comment': 'Bounded baseline/candidate real-worker comparison on fresh synthetic accounts'}
    request_path = reports / (name + '-request.json')
    request_path.write_text(json.dumps(request))
    command = aws('ssm', 'send-command', '--cli-input-json', 'file://' + str(request_path))['Command']['CommandId']
    metadata = {'commandId': command, 'instance': deployment['instance'], 'artifactSha256': deployment['artifactSha256'],
                'hostArtifacts': artifacts, 'runtimeArtifacts': runtime, 'workers': args.workers,
                'idleSeconds': args.idle_seconds, 'samples': args.samples, 'backlog': args.backlog,
                'controllerSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                'keys': ['reports/' + name + '-' + str(i) + '.jsonl' for i in range(4)]}
    (reports / (name + '-command.json')).write_text(json.dumps(metadata, indent=2))
    (reports / 'worker-latest-command.json').write_text(json.dumps(metadata, indent=2))
    print(json.dumps(metadata))


if __name__ == '__main__':
    main()
