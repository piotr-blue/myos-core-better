#!/usr/bin/env python3
"""Compare pinned host binaries by replaying the same signed histories on the isolated stack."""
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


def commands(deployment, artifacts, runtime_files, name, samples):
    if not re.fullmatch(r'recovery-[a-z0-9-]{1,80}', name) or not 1 <= samples <= 5:
        raise ValueError('Fresh recovery name and1..5 samples required')
    if set(artifacts) != {'baseline', 'candidate', 'tool'} or any(
            not re.fullmatch(r'[0-9a-f]{64}', value) for value in artifacts.values()):
        raise ValueError('Three pinned executable artifacts required')
    for filename, digest in runtime_files.items():
        if (not re.fullmatch(r'[A-Za-z0-9._-]+\.jar', filename)
                or filename.startswith('myos-core-better') or not re.fullmatch(r'[0-9a-f]{64}', digest)):
            raise ValueError('Pin every common dependency and exclude fallback host artifacts')
    if not any(name.startswith('blue-java-better-') for name in runtime_files):
        raise ValueError('A pinned common Blue library is required')
    release = '/opt/myos-better/releases/' + deployment['artifactSha256']
    bucket = deployment['bucket']
    remote = {role: '/var/lib/myos-better/recovery-' + digest + '.jar' for role, digest in artifacts.items()}
    fixture = '/var/lib/myos-better/' + name + '-fixture.json'
    health = deployment_runtime.health_check('canonical', combined_acknowledgements=True)
    result = ['set -euo pipefail', 'exec 9>/var/lock/myos-better-deploy.lock; flock -n 9',
              'test "$(readlink -f /opt/myos-better/current)" = ' + shlex.quote(release),
              'pid=$(systemctl show myos-better.service -p MainPID --value); test "$pid" -gt 0',
              'test "$(readlink -f /proc/$pid/cwd)" = ' + shlex.quote(release), health]
    for role, digest in artifacts.items():
        result.append(shlex.join(['aws', 's3', 'cp', 's3://' + bucket + '/releases/tools/recovery-' + digest + '.jar',
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

    def workload(role, mode, parameter, output):
        classpath = ':'.join([remote[role]] + [release + '/lib/' + p for p in sorted(runtime_files)] + [remote['tool']])
        command = shlex.join(['sudo', '-u', 'myos-better', 'env', 'MYOS_TABLE=' + deployment['table'],
            'AWS_REGION=eu-west-1', '/usr/bin/timeout', '--signal=TERM', '--kill-after=15s', '180s',
            '/usr/bin/java', '-Xms128m', '-Xmx768m', '-cp', classpath,
            'myos.better.TimelineRecoveryProbe', mode, fixture, str(parameter)])
        return ['result=0; ' + command + ' > ' + shlex.quote(output) + ' 2>&1 || result=$?',
                shlex.join(['aws', 's3', 'cp', output, 's3://' + bucket + '/reports/' + Path(output).name,
                            '--region', 'eu-west-1', '--only-show-errors']),
                'if [ "$result" -ne 0 ]; then tail -n 15 ' + shlex.quote(output) + '; exit "$result"; fi']

    result += workload('baseline', 'seed', name.replace('recovery-', 'recovery-bench-', 1),
                       '/var/lib/myos-better/' + name + '-seed.jsonl')
    result.append(shlex.join(['aws', 's3', 'cp', fixture, 's3://' + bucket + '/reports/' + name + '-fixture.json',
                             '--region', 'eu-west-1', '--only-show-errors']))
    summary = ('import json,sys; v=json.loads(open(sys.argv[1]).read().splitlines()[-1]); '
               'print(json.dumps({k:v[k] for k in ["status","hostSha256","librarySha256","fixtureSha256"]}))')
    for index, variant in enumerate(['baseline', 'candidate', 'candidate', 'baseline']):
        output = '/var/lib/myos-better/' + name + '-' + str(index) + '.jsonl'
        result += workload(variant, 'replay', samples, output)
        result.append(shlex.join(['python3', '-c', summary, output]))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline-host', type=Path, required=True)
    parser.add_argument('--samples', type=int, default=3)
    args = parser.parse_args()
    if not 1 <= args.samples <= 5: parser.error('Samples must be1..5')
    verify()
    root = Path(__file__).resolve().parents[1]
    reports = root / 'reports'
    deployment = json.loads((reports / 'deployment.json').read_text())
    outputs = {row['OutputKey']: row['OutputValue'] for row in
               aws('cloudformation', 'describe-stacks', '--stack-name', STACK)['Stacks'][0]['Outputs']}
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
    name = 'recovery-' + uuid.uuid4().hex
    generated = commands(deployment, artifacts, runtime, name, args.samples)
    for role, path in paths.items():
        aws('s3api', 'put-object', '--bucket', deployment['bucket'],
            '--key', 'releases/tools/recovery-' + artifacts[role] + '.jar', '--body', str(path))
    request = {'DocumentName': 'AWS-RunShellScript', 'InstanceIds': [deployment['instance']],
               'Parameters': {'commands': generated, 'executionTimeout': ['1200']},
               'Comment': 'Same-history baseline/candidate cold archive replay with full signature verification'}
    path = reports / (name + '-request.json'); path.write_text(json.dumps(request))
    command = aws('ssm', 'send-command', '--cli-input-json', 'file://' + str(path))['Command']['CommandId']
    metadata = {'commandId': command, 'instance': deployment['instance'], 'artifactSha256': deployment['artifactSha256'],
                'hostArtifacts': artifacts, 'runtimeArtifacts': runtime, 'samples': args.samples,
                'controllerSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                'keys': ['reports/' + name + '-' + str(i) + '.jsonl' for i in range(4)],
                'fixtureKey': 'reports/' + name + '-fixture.json', 'seedKey': 'reports/' + name + '-seed.jsonl'}
    (reports / (name + '-command.json')).write_text(json.dumps(metadata, indent=2))
    (reports / 'recovery-latest-command.json').write_text(json.dumps(metadata, indent=2))
    print(json.dumps(metadata))


if __name__ == '__main__':
    main()
