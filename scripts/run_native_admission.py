#!/usr/bin/env python3
"""Qualify signed ingress on fresh documents with pinned artifacts and bounded execution."""
import argparse
import hashlib
import json
import re
import shlex
import uuid
from pathlib import Path

from aws import aws, verify, STACK
import deployment_runtime


def check_deployment(deployment, outputs, status):
    profile = deployment_runtime.settings('canonical')
    if (status != 'Success'
            or not re.fullmatch(r'[0-9a-f]{64}', deployment.get('artifactSha256', ''))
            or deployment.get('runtimeProfile') != 'canonical'
            or deployment.get('edition') != profile['edition']
            or deployment.get('supportedEditions') != profile['supportedEditions']
            or deployment.get('combinedAcknowledgements') is not True):
        raise RuntimeError('A successful exact canonical deployment with combined acknowledgments is required')
    if any(deployment[key] != outputs[name] for key, name in
           [('instance', 'Instance'), ('table', 'Table'), ('bucket', 'Artifacts')]):
        raise RuntimeError('Deployment does not belong to this isolated stack')


def commands(deployment, tool_digest, runtime_files, name, account, warmups, samples, protocol='synthetic', secret_name=None):
    if protocol not in ('synthetic', 'published-timeline', 'timeline-service', 'timeline-directory', 'automatic-timeline', 'automatic-directory', 'private-directory'):
        raise ValueError('Unknown qualification protocol')
    if not re.fullmatch(r'native-bench-[A-Za-z0-9._-]{1,80}', account):
        raise ValueError('New synthetic native-bench-* account required')
    if not (0 <= warmups <= 64 and 1 <= samples <= 64):
        raise ValueError('Invalid qualification bounds')
    if protocol in ('automatic-timeline', 'automatic-directory') and (warmups > 16 or samples > 32):
        raise ValueError('Automatic qualification requires at most 16 warmups and 32 samples')
    if protocol == 'private-directory':
        if (not re.fullmatch(r'native-bench-[a-f0-9]{32}', account)
                or secret_name != 'myos-better-qualification-reader-' + account.removeprefix('native-bench-')
                or (warmups, samples) != (0, 1)):
            raise ValueError('Private custody requires a fresh exact secret scope and its fixed bounded scenario')
    elif secret_name is not None:
        raise ValueError('Reader custody is only valid for private directory qualification')
    release = '/opt/myos-better/releases/' + deployment['artifactSha256']
    remote_tool = '/var/lib/myos-better/native-qualification-' + tool_digest + '.jar'
    log = '/var/lib/myos-better/' + name + '.jsonl'
    tool_key = 'releases/tools/qualification-' + tool_digest + '.jar'
    digest_lines = [tool_digest + '  ' + remote_tool]
    for filename, digest in runtime_files.items():
        if not re.fullmatch(r'[A-Za-z0-9._-]+\.jar', filename) or not re.fullmatch(r'[0-9a-f]{64}', digest):
            raise ValueError('Invalid pinned runtime artifact')
        digest_lines.append(digest + '  ' + release + '/lib/' + filename)
    health = deployment_runtime.health_check('canonical', combined_acknowledgements=True)
    workload = shlex.join([
        'sudo', '-u', 'myos-better', 'env', 'MYOS_TABLE=' + deployment['table'], 'AWS_REGION=eu-west-1',
        '/usr/bin/timeout', '--signal=TERM', '--kill-after=15s', '240s', '/usr/bin/java',
        '-Xms128m', '-Xmx768m', '-cp', release + '/lib/*:' + remote_tool,
        ({'synthetic': 'myos.better.NativeAdmissionProbe',
          'published-timeline': 'myos.better.PublishedTimelineProbe',
          'timeline-service': 'myos.better.TimelineServiceProbe',
          'timeline-directory': 'myos.better.TimelineServiceProbe',
          'automatic-timeline': 'myos.better.AutomaticTimelineProbe', 'automatic-directory': 'myos.better.AutomaticTimelineProbe',
          'private-directory': 'myos.better.PrivateTimelineServiceProbe'}[protocol])]
        + ([account, secret_name] if protocol == 'private-directory' else [account, str(warmups), str(samples)]) + (['directory'] if protocol in ('timeline-directory', 'automatic-directory') else []))
    result = [
        'set -euo pipefail',
        'exec 9>/var/lock/myos-better-deploy.lock; flock -n 9',
        'test "$(readlink -f /opt/myos-better/current)" = ' + shlex.quote(release),
        'pid=$(systemctl show myos-better.service -p MainPID --value); test "$pid" -gt 0',
        'test "$(readlink -f /proc/$pid/cwd)" = ' + shlex.quote(release),
        health,
        shlex.join(['aws', 's3', 'cp', 's3://' + deployment['bucket'] + '/' + tool_key,
                    remote_tool, '--region', 'eu-west-1', '--only-show-errors']),
        "printf '%s\\n' " + shlex.quote('\n'.join(digest_lines)) + ' | sha256sum --check --status',
        'chmod 644 ' + shlex.quote(remote_tool),
        'result=0; ' + workload + ' > ' + shlex.quote(log) + ' 2>&1 || result=$?',
        shlex.join(['aws', 's3', 'cp', log, 's3://' + deployment['bucket'] + '/reports/' + name + '.jsonl',
                    '--region', 'eu-west-1', '--only-show-errors']),
        'tail -n 1 ' + shlex.quote(log),
        'test "$(systemctl show myos-better.service -p MainPID --value)" -eq "$pid"',
        health,
        'test "$result" -eq 0',
    ]
    if protocol in ('published-timeline', 'timeline-service', 'timeline-directory', 'automatic-timeline', 'automatic-directory', 'private-directory'):
        # Its native child emits durable parent work. The main service does not install these
        # temporary provider bindings, so isolate its workers and restore the exact profile.
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
        workload_index = next(i for i, line in enumerate(result) if line.startswith('result=0;'))
        result[workload_index:workload_index] = [restore, 'systemctl stop myos-better.service']
        result.remove('test "$(systemctl show myos-better.service -p MainPID --value)" -eq "$pid"')
        # The EXIT trap performs the post-workload health assertion after restarting.
        del result[-2]
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--warmups', type=int, default=16)
    parser.add_argument('--samples', type=int, default=32)
    parser.add_argument('--protocol', choices=['synthetic', 'published-timeline', 'timeline-service', 'timeline-directory', 'automatic-timeline', 'automatic-directory'], default='synthetic')
    args = parser.parse_args()
    if not (0 <= args.warmups <= 64 and 1 <= args.samples <= 64):
        parser.error('Warmups must be0..64 and samples1..64')
    if args.protocol in ('automatic-timeline', 'automatic-directory') and (args.warmups > 16 or args.samples > 32):
        parser.error('Automatic qualification requires at most16 warmups and32 samples')
    verify()
    root = Path(__file__).resolve().parents[1]
    reports = root / 'reports'
    deployment = json.loads((reports / 'deployment.json').read_text())
    outputs = {row['OutputKey']: row['OutputValue'] for row in
               aws('cloudformation', 'describe-stacks', '--stack-name', STACK)['Stacks'][0]['Outputs']}
    status = aws('ssm', 'get-command-invocation', '--command-id', deployment['commandId'],
                 '--instance-id', deployment['instance'])
    check_deployment(deployment, outputs, status['Status'])
    tools = list((root / 'build/libs').glob('*-qualification.jar'))
    if len(tools) != 1:
        raise RuntimeError('Build exactly one qualification JAR')
    runtime_files = {}
    for pattern in ['blue-java-better-*.jar', 'myos-core-better-*.jar']:
        selected = list((root / 'build/install/myos-core-better/lib').glob(pattern))
        if len(selected) != 1:
            raise RuntimeError('Build an unambiguous installed host/library distribution')
        runtime_files[selected[0].name] = hashlib.sha256(selected[0].read_bytes()).hexdigest()
    tool = tools[0]
    digest = hashlib.sha256(tool.read_bytes()).hexdigest()
    aws('s3api', 'put-object', '--bucket', deployment['bucket'],
        '--key', 'releases/tools/qualification-' + digest + '.jar', '--body', str(tool))
    identifier = uuid.uuid4().hex
    account = 'native-bench-' + identifier
    name = deployment['artifactSha256'][:12] + '-native-' + identifier[:8]
    request = {'DocumentName': 'AWS-RunShellScript', 'InstanceIds': [deployment['instance']],
               'Parameters': {'commands': commands(deployment, digest, runtime_files, name, account,
                                                    args.warmups, args.samples, args.protocol),
                              'executionTimeout': ['300']},
               'Comment': 'Bounded signed ingress qualification: ' + args.protocol}
    request_path = reports / (name + '-request.json')
    request_path.write_text(json.dumps(request))
    command = aws('ssm', 'send-command', '--cli-input-json', 'file://' + str(request_path))['Command']['CommandId']
    metadata = {'commandId': command, 'instance': deployment['instance'],
                'artifactSha256': deployment['artifactSha256'], 'probeSha256': digest,
                'runtimeArtifacts': runtime_files, 'account': account,
                'key': 'reports/' + name + '.jsonl', 'warmups': args.warmups, 'samples': args.samples,
                'protocol': args.protocol}
    (reports / (name + '-command.json')).write_text(json.dumps(metadata, indent=2))
    (reports / 'native-latest-command.json').write_text(json.dumps(metadata, indent=2))
    print(json.dumps(metadata))


if __name__ == '__main__':
    main()
