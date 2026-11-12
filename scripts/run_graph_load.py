#!/usr/bin/env python3
"""Exercise existing synthetic graphs through real workers; never rebuild or select user documents."""
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


def commands(deployment, tool_digest, runtime, name, populations, payments, operations, clients, active_orders=0):
    if not re.fullmatch(r'graph-load-[a-z0-9-]{1,60}', name):
        raise ValueError('Invalid qualification name')
    if not re.fullmatch(r'[0-9a-f]{64}', tool_digest):
        raise ValueError('Unpinned qualification tool')
    if len(populations) != 2 or populations[0][0] == populations[1][0]:
        raise ValueError('Two distinct synthetic accounts required')
    if any(not re.fullmatch(r'bench-[A-Za-z0-9._-]{1,90}', account) or not 1 <= orders <= 100000
           for account, orders in populations):
        raise ValueError('Bounded existing synthetic populations required')
    if not (1 <= payments <= 8 and 1 <= operations <= 512 and 1 <= clients <= 16):
        raise ValueError('Invalid bounded workload dimensions')
    if not 0 <= active_orders <= min(orders for _, orders in populations):
        raise ValueError('Active orders must fit both existing populations')
    if sum(n.startswith('myos-core-better-') for n in runtime) != 1 or sum(n.startswith('blue-java-better-') for n in runtime) != 1:
        raise ValueError('Pinned host and library required')
    if any(not re.fullmatch(r'[A-Za-z0-9._-]+\.jar', n) or not re.fullmatch(r'[0-9a-f]{64}', h)
           for n, h in runtime.items()):
        raise ValueError('Invalid runtime artifact')
    release = '/opt/myos-better/releases/' + deployment['artifactSha256']
    remote_tool = '/var/lib/myos-better/qualification-' + tool_digest + '.jar'
    health = deployment_runtime.health_check('canonical', combined_acknowledgements=True)
    result = ['set -euo pipefail', 'exec 9>/var/lock/myos-better-deploy.lock; flock -n 9',
              'test "$(readlink -f /opt/myos-better/current)" = ' + shlex.quote(release),
              'pid=$(systemctl show myos-better.service -p MainPID --value); test "$pid" -gt 0',
              'test "$(readlink -f /proc/$pid/cwd)" = ' + shlex.quote(release), health,
              shlex.join(['aws', 's3', 'cp', 's3://' + deployment['bucket'] + '/releases/tools/qualification-' + tool_digest + '.jar',
                          remote_tool, '--region', 'eu-west-1', '--only-show-errors'])]
    hashes = [tool_digest + '  ' + remote_tool] + [digest + '  ' + release + '/lib/' + file for file, digest in runtime.items()]
    result += ["printf '%s\\n' " + shlex.quote('\n'.join(hashes)) + ' | sha256sum --check --status',
               'chmod 644 ' + shlex.quote(remote_tool)]
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
    classpath = ':'.join([release + '/lib/' + file for file in sorted(runtime)] + [remote_tool])
    summary = summary_code()
    for index, population in enumerate([populations[0], populations[1], populations[1], populations[0]]):
        account, orders = population
        log = '/var/lib/myos-better/' + name + '-' + str(index) + '.jsonl'
        workload = shlex.join(['sudo', '-u', 'myos-better', 'env', 'MYOS_TABLE=' + deployment['table'],
            'AWS_REGION=eu-west-1', 'MYOS_RUNTIME=' + release + '/examples/runtime/canonical.json', '/usr/bin/timeout', '--signal=TERM', '--kill-after=15s', '180s',
            '/usr/bin/java', '-Xms256m', '-Xmx2g', '-XX:+ExitOnOutOfMemoryError', '-cp', classpath, 'myos.better.GraphLoadProbe',
            account, str(orders), str(payments), str(operations), str(clients), str(active_orders)])
        result += ['result=0; ' + workload + ' > ' + shlex.quote(log) + ' 2>&1 || result=$?',
                   shlex.join(['aws', 's3', 'cp', log, 's3://' + deployment['bucket'] + '/reports/' + name + '-' + str(index) + '.jsonl',
                               '--region', 'eu-west-1', '--only-show-errors']),
                   'if [ "$result" -ne 0 ]; then tail -n 15 ' + shlex.quote(log) + '; exit "$result"; fi',
                   shlex.join(['python3', '-c', summary, log])]
    return result


def summary_code():
    return """import json,sys
rows=[]
for line in open(sys.argv[1]):
    try: value=json.loads(line)
    except json.JSONDecodeError: continue
    if isinstance(value,dict) and value.get('status')=='PASSED': rows.append(value)
if len(rows)!=1: raise RuntimeError('Expected exactly one passing qualification record')
v=rows[0]
print(json.dumps({'status':v['status'],'orders':v['orders'],
    'sequentialMedianMillis':v['sequential']['sequentialCompletionMedianMillis'],
    'concurrentCompletionMillis':v['concurrent']['completionMillis']}))
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--small-account', required=True)
    parser.add_argument('--small-orders', type=int, required=True)
    parser.add_argument('--large-account', required=True)
    parser.add_argument('--large-orders', type=int, required=True)
    parser.add_argument('--payments', type=int, default=3)
    parser.add_argument('--operations', type=int, default=64)
    parser.add_argument('--clients', type=int, default=8)
    parser.add_argument('--active-orders', type=int, default=0,
                        help='Evenly spaced active order subset shared by both populations; 0 samples all')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    reports = root / 'reports'
    deployment = json.loads((reports / 'deployment.json').read_text())
    tools = list((root / 'build/libs').glob('*-qualification.jar'))
    if len(tools) != 1:
        raise RuntimeError('Build one qualification JAR first')
    tool = tools[0]
    digest = hashlib.sha256(tool.read_bytes()).hexdigest()
    runtime = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in
               sorted((root / 'build/install/myos-core-better/lib').glob('*.jar'))}
    populations = [(args.small_account, args.small_orders), (args.large_account, args.large_orders)]
    name = 'graph-load-' + uuid.uuid4().hex
    generated = commands(deployment, digest, runtime, name, populations, args.payments, args.operations, args.clients, args.active_orders)
    verify()
    outputs = {x['OutputKey']: x['OutputValue'] for x in
               aws('cloudformation', 'describe-stacks', '--stack-name', STACK)['Stacks'][0]['Outputs']}
    status = aws('ssm', 'get-command-invocation', '--command-id', deployment['commandId'], '--instance-id', deployment['instance'])
    check_deployment(deployment, outputs, status['Status'])
    aws('s3api', 'put-object', '--bucket', deployment['bucket'], '--key', 'releases/tools/qualification-' + digest + '.jar', '--body', str(tool))
    request = {'DocumentName': 'AWS-RunShellScript', 'InstanceIds': [deployment['instance']],
               'Parameters': {'commands': generated, 'executionTimeout': ['900']},
               'Comment': 'Bounded real-worker qualification on existing synthetic small and large graphs'}
    path = reports / (name + '-request.json')
    path.write_text(json.dumps(request, indent=2))
    identifier = aws('ssm', 'send-command', '--cli-input-json', 'file://' + str(path))['Command']['CommandId']
    result = dict(commandId=identifier, instance=deployment['instance'], artifactSha256=deployment['artifactSha256'],
                  toolSha256=digest, controllerSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(), runtimeArtifacts=runtime, populations=populations, payments=args.payments,
                  operations=args.operations, clients=args.clients, activeOrders=args.active_orders, requestSha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                  keys=['reports/' + name + '-' + str(i) + '.jsonl' for i in range(4)])
    (reports / 'graph-load-latest-command.json').write_text(json.dumps(result, indent=2))
    print(json.dumps(result))


if __name__ == '__main__':
    main()
