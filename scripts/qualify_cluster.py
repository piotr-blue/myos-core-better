#!/usr/bin/env python3
"""Bounded two-AZ qualification; delete the temporary peer and restore the primary on exit."""
import hashlib
import json
import shlex
import subprocess
import time
import uuid
from pathlib import Path

from aws import aws, verify, STACK


class Qualification:
    def __init__(self):
        self.root = Path(__file__).resolve().parents[1]
        self.reports = self.root / 'reports'
        self.deployment = json.loads((self.reports / 'deployment.json').read_text())
        self.run = 'cluster-' + uuid.uuid4().hex[:16]
        self.stack = STACK + '-peer-' + self.run[-8:]
        self.commands = []
        self.peer = None
        self.created = False
        self.guarded = False
        self.release = '/opt/myos-better/releases/' + self.deployment['artifactSha256']
        self.remote_script = '/var/lib/myos-better/' + self.run + '.py'
        self.remote_manifest = '/var/lib/myos-better/' + self.run + '-manifest.json'
        self.guard = 'myos-better-cluster-' + self.run[-8:]

    def save(self, suffix, value):
        path = self.reports / (self.run + '-' + suffix + '.json')
        path.write_text(json.dumps(value, indent=2) + '\n')
        return path

    def send(self, instance, phase, commands, timeout=300):
        request = {'DocumentName': 'AWS-RunShellScript', 'InstanceIds': [instance],
                   'Parameters': {'commands': ['set -euo pipefail'] + commands,
                                  'executionTimeout': [str(timeout)]},
                   'Comment': 'Isolated two-AZ MyOS qualification: ' + phase}
        path = self.save(phase + '-request', request)
        command = aws('ssm', 'send-command', '--cli-input-json', 'file://' + str(path))['Command']['CommandId']
        self.commands.append({'instance': instance, 'phase': phase, 'commandId': command})
        self.save('commands', self.commands)
        return instance, command

    def wait(self, handle, timeout=360):
        instance, command = handle
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                result = aws('ssm', 'get-command-invocation', '--command-id', command,
                             '--instance-id', instance)
            except subprocess.CalledProcessError as error:
                if 'InvocationDoesNotExist' not in error.stderr:
                    raise
                time.sleep(2)
                continue
            if result['Status'] == 'Success':
                return result
            if result['Status'] not in ('Pending', 'InProgress', 'Delayed'):
                self.save('failure-' + command, result)
                raise RuntimeError('SSM qualification failed: ' + command + ' ' + result['Status'])
            time.sleep(2)
        raise TimeoutError('SSM qualification timed out: ' + command)

    def execute(self, instance, phase, commands, timeout=300):
        return self.wait(self.send(instance, phase, commands, timeout), timeout + 60)

    def upload(self, path, label):
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        key = 'releases/tools/' + label + '-' + digest + path.suffix
        aws('s3api', 'put-object', '--bucket', self.deployment['bucket'], '--key', key, '--body', str(path))
        return key, digest

    def download_command(self, key, digest, destination):
        return [shlex.join(['aws', 's3', 'cp', 's3://' + self.deployment['bucket'] + '/' + key,
                            destination, '--region', 'eu-west-1', '--only-show-errors']),
                'echo ' + shlex.quote(digest + '  ' + destination) + ' | sha256sum --check --status',
                'chmod 644 ' + shlex.quote(destination)]

    def collect(self, phase):
        path = self.reports / (self.run + '-' + phase + '.json')
        aws('s3api', 'get-object', '--bucket', self.deployment['bucket'],
            '--key', 'reports/' + path.name, str(path))
        return json.loads(path.read_text())

    def phase(self, instance, phase, mode, extra=()):
        output = '/var/lib/myos-better/' + self.run + '-' + phase + '.json'
        command = ['sudo', '-u', 'myos-better', 'python3', self.remote_script, mode,
                   '--run', self.run, '--output', output, '--examples', self.release + '/examples',
                   '--manifest', self.remote_manifest, *extra]
        return self.send(instance, phase, [shlex.join(command), shlex.join([
            'aws', 's3', 'cp', output, 's3://' + self.deployment['bucket'] + '/reports/' + Path(output).name,
            '--region', 'eu-west-1', '--only-show-errors'])], 360)

    @staticmethod
    def health():
        return ('healthy=0; for i in $(seq 1 45); do if curl --fail --silent '
                'http://127.0.0.1:8080/health | python3 -c '
                + shlex.quote('import json,sys; r=json.load(sys.stdin); '
                              'assert r["status"]=="ok" and r["durable"] is True')
                + '; then healthy=1; break; fi; sleep 1; done; '
                'test "$healthy" -eq 1')

    def provision(self):
        verify()
        d = self.deployment
        outputs = {row['OutputKey']: row['OutputValue'] for row in
                   aws('cloudformation', 'describe-stacks', '--stack-name', STACK)['Stacks'][0]['Outputs']}
        if any(d[key] != outputs[name] for key, name in
               [('instance', 'Instance'), ('table', 'Table'), ('bucket', 'Artifacts')]):
            raise RuntimeError('Deployment is not the isolated experiment')
        if aws('ssm', 'get-command-invocation', '--command-id', d['commandId'],
               '--instance-id', d['instance'])['Status'] != 'Success':
            raise RuntimeError('A healthy pinned deployment is required')
        primary = aws('ec2', 'describe-instances', '--instance-ids', d['instance'])['Reservations'][0]['Instances'][0]
        subnets = aws('ec2', 'describe-subnets', '--filters', 'Name=vpc-id,Values=' + primary['VpcId'])['Subnets']
        candidates = sorted((s for s in subnets if s['MapPublicIpOnLaunch'] and
                             s['AvailabilityZone'] != primary['Placement']['AvailabilityZone']),
                            key=lambda s: s['AvailabilityZone'])
        if not candidates:
            raise RuntimeError('No other-AZ subnet available in the experiment VPC')
        subnet = candidates[0]
        self.zones = [primary['Placement']['AvailabilityZone'], subnet['AvailabilityZone']]
        # Reuse only the experiment's restricted role and no-ingress security group.
        host = {'Type': 'AWS::EC2::Instance', 'Properties': {
            'ImageId': primary['ImageId'], 'InstanceType': primary['InstanceType'],
            'IamInstanceProfile': primary['IamInstanceProfile']['Arn'].rsplit('/', 1)[1],
            'SubnetId': subnet['SubnetId'],
            'SecurityGroupIds': [row['GroupId'] for row in primary['SecurityGroups']],
            'MetadataOptions': {'HttpTokens': 'required', 'HttpEndpoint': 'enabled'},
            'BlockDeviceMappings': [{'DeviceName': '/dev/xvda', 'Ebs': {
                'Encrypted': True, 'VolumeSize': 30, 'VolumeType': 'gp3', 'DeleteOnTermination': True}}],
            'Tags': [{'Key': 'Name', 'Value': self.stack}, {'Key': 'project', 'Value': STACK},
                     {'Key': 'purpose', 'Value': 'temporary-two-az-qualification'}],
            'UserData': {'Fn::Base64': '#!/bin/bash\nset -euo pipefail\n'
                         'systemd-run --unit=myos-peer-cost-bound --on-active=3h /sbin/shutdown -h now\n'
                         'dnf install -y java-25-amazon-corretto-devel\n'
                         'useradd --system --home-dir /opt/myos-better myos-better\n'
                         'install -d -o myos-better -g myos-better /opt/myos-better /var/lib/myos-better\n'}}}
        template = self.save('template', {'AWSTemplateFormatVersion': '2010-09-09',
                                         'Resources': {'Peer': host},
                                         'Outputs': {'Instance': {'Value': {'Ref': 'Peer'}}}})
        aws('cloudformation', 'create-stack', '--stack-name', self.stack,
            '--template-body', 'file://' + str(template), '--on-failure', 'DELETE')
        self.created = True
        self.save('resources', {'stack': self.stack, 'primary': d['instance'], 'zones': self.zones})
        print(json.dumps({'phase': 'provision', 'temporaryStack': self.stack, 'zones': self.zones}), flush=True)
        deadline = time.monotonic() + 900
        while time.monotonic() < deadline:
            stack = aws('cloudformation', 'describe-stacks', '--stack-name', self.stack)['Stacks'][0]
            if stack['StackStatus'] == 'CREATE_COMPLETE':
                self.peer = next(row['OutputValue'] for row in stack['Outputs'] if row['OutputKey'] == 'Instance')
                break
            if stack['StackStatus'] != 'CREATE_IN_PROGRESS':
                raise RuntimeError('Temporary host provisioning failed: ' + stack['StackStatus'])
            time.sleep(5)
        if not self.peer:
            raise TimeoutError('Temporary host provisioning timed out')
        self.save('resources', {'stack': self.stack, 'primary': d['instance'], 'peer': self.peer, 'zones': self.zones})
        deadline = time.monotonic() + 600
        while time.monotonic() < deadline:
            info = aws('ssm', 'describe-instance-information', '--filters', 'Key=InstanceIds,Values=' + self.peer)
            if any(row['PingStatus'] == 'Online' for row in info['InstanceInformationList']):
                break
            time.sleep(5)
        else:
            raise TimeoutError('Temporary host did not register with SSM')

    def install(self):
        d = self.deployment
        digest = d['artifactSha256']
        unit = '''[Unit]
Description=Temporary MyOS two-AZ qualification
After=network-online.target
[Service]
User=myos-better
Group=myos-better
WorkingDirectory=@RELEASE@
ExecStart=@RELEASE@/bin/myos-core-better serve
Environment=AWS_REGION=eu-west-1
Environment=MYOS_TABLE=@TABLE@
Environment=PORT=8080
Environment=MYOS_WORKERS=8
Environment=MYOS_SESSION_ENTRIES=256
Environment=MYOS_SESSION_MIB=512
Environment=MYOS_SESSION_USES=32
Environment="JAVA_OPTS=-Xms256m -Xmx2g -XX:+ExitOnOutOfMemoryError"
Restart=on-failure
RestartSec=3
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true
ReadWritePaths=/var/lib/myos-better
MemoryMax=3G
TasksMax=1024
'''.replace('@RELEASE@', self.release).replace('@TABLE@', d['table'])
        self.execute(self.peer, 'install-peer', [
            'cloud-init status --wait', 'java -version',
            *self.download_command('releases/' + digest + '.tar.gz', digest, '/tmp/myos-peer-release.tar.gz'),
            'install -d -m 755 ' + shlex.quote(self.release),
            'tar -xzf /tmp/myos-peer-release.tar.gz -C ' + shlex.quote(self.release) + ' --strip-components=1',
            'chown -R root:root ' + shlex.quote(self.release),
            "cat > /etc/systemd/system/myos-better.service <<'MYOS_UNIT'\n" + unit + 'MYOS_UNIT',
            'systemctl daemon-reload', 'systemctl start myos-better.service', self.health()], 600)
        script = self.root / 'scripts/cluster_probe.py'
        key, digest = self.upload(script, 'cluster-probe')
        self.script_digest = digest
        for label, instance in [('primary', d['instance']), ('peer', self.peer)]:
            self.execute(instance, 'install-probe-' + label, self.download_command(key, digest, self.remote_script))
        # This bounded lock is held across SSM phases and prevents concurrent deployment/probes.
        self.execute(d['instance'], 'reserve-primary', [
            shlex.join(['systemd-run', '--unit=' + self.guard, '--collect',
                        '--property=ExecStopPost=/usr/bin/systemctl start myos-better.service',
                        'flock', '-n', '/var/lock/myos-better-deploy.lock', 'sleep', '1800']),
            'sleep 1', 'systemctl is-active --quiet ' + self.guard + '.service'])
        self.guarded = True
        print(json.dumps({'phase': 'installed', 'peer': self.peer, 'artifact': d['artifactSha256']}), flush=True)

    def qualify(self):
        primary = self.deployment['instance']
        self.wait(self.phase(primary, 'prepare', 'prepare'))
        manifest = self.collect('prepare')
        key, digest = self.upload(self.reports / (self.run + '-prepare.json'), 'cluster-manifest')
        for label, instance in [('primary', primary), ('peer', self.peer)]:
            self.execute(instance, 'manifest-' + label, self.download_command(key, digest, self.remote_manifest))
        start = time.time() + 40
        handles = [self.phase(instance, 'race-' + label, 'race', ['--start', str(start)])
                   for label, instance in [('primary', primary), ('peer', self.peer)]]
        print(json.dumps({'phase': 'racing', 'accounts': 4, 'uniqueCommands': 128, 'submittedCalls': 256}), flush=True)
        for handle in handles:
            self.wait(handle, 420)
        races = [self.collect('race-' + label) for label in ['primary', 'peer']]
        receipts = [{(row['account'], row['operation']): row['result'] for row in race['outcomes']} for race in races]
        if len(receipts[0]) != 128 or receipts[0] != receipts[1]:
            raise RuntimeError('Machines returned different committed receipts for the same commands')
        handles = [self.phase(instance, 'verify-' + label, 'verify')
                   for label, instance in [('primary', primary), ('peer', self.peer)]]
        for handle in handles:
            self.wait(handle, 420)
        views = [self.collect('verify-' + label) for label in ['primary', 'peer']]
        if views[0] != views[1]:
            raise RuntimeError('Machines disagree about fully propagated graph views')
        print(json.dumps({'phase': 'race-passed', 'allReceiptsEqual': True, 'allViewsEqual': True}), flush=True)
        recovery = self.recover()
        report = {'run': self.run, 'artifactSha256': self.deployment['artifactSha256'],
                  'scriptSha256': self.script_digest, 'zones': self.zones,
                  'instances': [primary, self.peer], 'accounts': len(manifest['accounts']),
                  'uniqueCommands': 128, 'submittedCalls': 256,
                  'allDuplicateReceiptsEqual': True, 'allGraphViewsEqual': True,
                  'conflictingPayloadRejected': True, 'allLeafOrderRootTotalsChecked': True,
                  'raceSeconds': [race['seconds'] for race in races],
                  'callMedianMillis': [race['callMedianMillis'] for race in races],
                  'crossMachineRecovery': recovery,
                  'method': 'Two c7i.large machines in separate AZs; shared DynamoDB, loopback clients; bounded correctness qualification, not HA or sustained-load certification'}
        self.save('report', report)
        return report

    def recover(self):
        primary = self.deployment['instance']
        probes = list((self.root / 'build/libs').glob('*-qualification.jar'))
        if len(probes) != 1:
            raise RuntimeError('Build exactly one qualificationJar before running')
        key, digest = self.upload(probes[0], 'cluster-recovery')
        remote = '/var/lib/myos-better/' + self.run + '-qualification.jar'
        self.execute(primary, 'install-recovery', self.download_command(key, digest, remote))
        for label, instance in [('primary', primary), ('peer', self.peer)]:
            self.execute(instance, 'stop-' + label, ['systemctl stop myos-better.service'])
        account = 'recovery-' + self.run
        output = '/var/lib/myos-better/' + self.run + '-recovery-before.json'
        self.execute(primary, 'commit-without-worker', [shlex.join([
            'sudo', '-u', 'myos-better', 'env', 'MYOS_TABLE=' + self.deployment['table'],
            'AWS_REGION=eu-west-1', '/usr/bin/java', '-Xms128m', '-Xmx1g', '-cp',
            self.release + '/lib/*:' + remote, 'myos.better.RecoveryProbe', account]) + ' > ' + shlex.quote(output),
            shlex.join(['aws', 's3', 'cp', output,
                        's3://' + self.deployment['bucket'] + '/reports/' + Path(output).name,
                        '--region', 'eu-west-1', '--only-show-errors'])])
        before = self.collect('recovery-before')
        self.execute(self.peer, 'recover-on-peer', ['systemctl start myos-better.service', self.health()])
        output = '/var/lib/myos-better/' + self.run + '-recovery-after.json'
        self.execute(self.peer, 'verify-recovery', [shlex.join([
            'sudo', '-u', 'myos-better', 'python3', self.remote_script, 'recover', '--run', account,
            '--output', output]), shlex.join(['aws', 's3', 'cp', output,
                's3://' + self.deployment['bucket'] + '/reports/' + Path(output).name,
                '--region', 'eu-west-1', '--only-show-errors'])])
        after = self.collect('recovery-after')
        self.execute(primary, 'restore-primary', ['systemctl start myos-better.service', self.health()])
        return {'before': before, 'after': after, 'producerExitedBeforeRecovery': True,
                'onlyOtherAzWorkerRunningDuringRecovery': True,
                'method': 'Both services stopped; standalone primary commits and exits with pending outbox; only peer restarts and recovers'}

    def cleanup(self):
        errors = []
        if self.guarded:
            try:
                self.execute(self.deployment['instance'], 'cleanup-primary', [
                    'systemctl start myos-better.service', self.health(),
                    'systemctl stop ' + self.guard + '.service'])
            except Exception as error:
                errors.append('Primary restoration: ' + str(error))
        if self.created:
            try:
                aws('cloudformation', 'delete-stack', '--stack-name', self.stack)
                deadline = time.monotonic() + 600
                while time.monotonic() < deadline:
                    try:
                        aws('cloudformation', 'describe-stacks', '--stack-name', self.stack)
                    except subprocess.CalledProcessError as error:
                        if 'does not exist' in error.stderr:
                            break
                        raise
                    time.sleep(5)
                else:
                    raise TimeoutError('Temporary stack deletion did not finish')
            except Exception as error:
                errors.append('Temporary stack cleanup: ' + str(error))
        self.save('cleanup', {'temporaryStack': self.stack, 'errors': errors,
                              'deleted': self.created and not any('stack' in e for e in errors)})
        if errors:
            raise RuntimeError('; '.join(errors))


def main():
    qualification = Qualification()
    report = None
    try:
        qualification.provision()
        qualification.install()
        report = qualification.qualify()
    finally:
        qualification.cleanup()
    report['temporaryPeerDeleted'] = True
    report['primaryRestoredHealthy'] = True
    qualification.save('report', report)
    qualification.save('latest', {'report': qualification.run + '-report.json'})
    print(json.dumps(report, indent=2), flush=True)


if __name__ == '__main__':
    main()
