"""Temporary, isolated AWS workers for explicit qualification; never clone live disks."""
import base64
import contextlib
import json
import time
import uuid

from aws import aws


def instance(identity):
    response = aws('ec2', 'describe-instances', '--instance-ids', identity)
    return response['Reservations'][0]['Instances'][0]


@contextlib.contextmanager
def worker(outputs, reports, selected=None, *, large_graph=False):
    """Use an isolated worker; terminate only instances created by this invocation."""
    worker_type = 'c7i.2xlarge' if large_graph else 'c7i.xlarge'
    minimum_mib = 16384 if large_graph else 8192
    created = False
    identity = selected
    try:
        if selected == outputs['Instance']:
            raise ValueError('Qualification must not share the live application host')
        if selected is None:
            source = instance(outputs['Instance'])
            bootstrap = ('#!/bin/bash\nset -euo pipefail\n'
                         'dnf install -y java-25-amazon-corretto-devel\n'
                         'useradd --system --home-dir /opt/myos-better myos-better\n')
            request = {
                'ImageId': source['ImageId'], 'InstanceType': worker_type,
                'MinCount': 1, 'MaxCount': 1, 'ClientToken': str(uuid.uuid4()),
                'SubnetId': source['SubnetId'],
                'SecurityGroupIds': [s['GroupId'] for s in source['SecurityGroups']],
                'IamInstanceProfile': {'Arn': source['IamInstanceProfile']['Arn']},
                'MetadataOptions': {'HttpTokens': 'required', 'HttpEndpoint': 'enabled'},
                'BlockDeviceMappings': [{'DeviceName': source['RootDeviceName'], 'Ebs': {
                    'Encrypted': True, 'VolumeSize': 30, 'VolumeType': 'gp3',
                    'DeleteOnTermination': True}}],
                'UserData': base64.b64encode(bootstrap.encode()).decode(),
                'TagSpecifications': [{'ResourceType': 'instance', 'Tags': [
                    {'Key': 'Name', 'Value': 'myos-owner-qualification-temporary'},
                    {'Key': 'purpose', 'Value': 'bounded-owner-qualification'},
                    {'Key': 'owner', 'Value': 'myos-core-better'}]}],
            }
            path = reports / 'qualification-host-request.json'
            path.write_text(json.dumps(request))
            response = aws('ec2', 'run-instances', '--cli-input-json', 'file://' + str(path))
            identity = response['Instances'][0]['InstanceId']
            created = True
            (reports / 'qualification-host.json').write_text(json.dumps({
                'instance': identity, 'temporary': True, 'type': worker_type}, indent=2))
            print(json.dumps({'phase': 'qualification-host-created', 'instance': identity}), flush=True)
        host = instance(identity)
        capacity = aws('ec2', 'describe-instance-types', '--instance-types', host['InstanceType'])
        if capacity['InstanceTypes'][0]['MemoryInfo']['SizeInMiB'] < minimum_mib:
            raise ValueError(f'Qualification requires at least {minimum_mib // 1024} GiB on the dedicated worker')
        deadline = time.monotonic() + 600
        while time.monotonic() < deadline:
            response = aws('ssm', 'describe-instance-information', '--filters', json.dumps([
                {'Key': 'InstanceIds', 'Values': [identity]}]))
            if any(i['PingStatus'] == 'Online' for i in response['InstanceInformationList']):
                yield identity
                return
            print(json.dumps({'phase': 'waiting-for-qualification-host', 'instance': identity}), flush=True)
            time.sleep(10)
        raise TimeoutError('Dedicated qualification worker did not register with SSM')
    finally:
        if created:
            aws('ec2', 'terminate-instances', '--instance-ids', identity)
            (reports / 'qualification-host-terminated.json').write_text(json.dumps({
                'instance': identity, 'terminationRequested': True}, indent=2))
            print(json.dumps({'phase': 'qualification-host-terminated', 'instance': identity}), flush=True)


def collect_stdout(bucket, prefix, destination):
    """Collect full bounded SSM stdout from its report prefix, not the truncated inline field."""
    if not prefix.startswith('reports/'):
        raise ValueError('Qualification output must stay within the report prefix')
    for attempt in range(15):
        listing = aws('s3api', 'list-objects-v2', '--bucket', bucket, '--prefix', prefix + '/',
                      '--max-keys', '1000')
        matches = [row for row in listing.get('Contents', []) if row['Key'].endswith('/stdout')]
        if listing.get('IsTruncated') or len(matches) > 1:
            raise RuntimeError('Ambiguous qualification output')
        if matches:
            selected = matches[0]
            if selected['Size'] > 16 * 1024 * 1024:
                raise RuntimeError('Qualification output exceeds 16 MiB')
            target = destination.with_suffix('.stdout.jsonl')
            aws('s3api', 'get-object', '--bucket', bucket, '--key', selected['Key'], str(target))
            if target.stat().st_size != selected['Size']:
                raise RuntimeError('Qualification output size changed during collection')
            return target.read_text()
        time.sleep(1)
    raise TimeoutError('Complete qualification output was not uploaded')


def wait(command, identity, destination, output=None, *, timeout=900):
    """Retain the bounded command result, including failure; never silently treat timeout as success."""
    deadline = time.monotonic() + timeout
    time.sleep(2)
    while time.monotonic() < deadline:
        result = aws('ssm', 'get-command-invocation', '--command-id', command,
                     '--instance-id', identity)
        destination.write_text(json.dumps(result, indent=2) + '\n')
        status = result['Status']
        if status not in ('Pending', 'InProgress', 'Delayed', 'Cancelling'):
            print(json.dumps({'phase': 'qualification-completed', 'status': status}), flush=True)
            if output is not None:
                try:
                    full = collect_stdout(*output, destination)
                    result['InlineStandardOutputContent'] = result.get('StandardOutputContent', '')
                    result['StandardOutputContent'] = full
                    result['OutputStorage'] = {'bucket': output[0], 'prefix': output[1]}
                except Exception as failure:
                    result['OutputCollectionError'] = type(failure).__name__
                    destination.write_text(json.dumps(result, indent=2) + '\n')
                    if status == 'Success':
                        raise RuntimeError('Qualification output incomplete; inspect ' + str(destination)) from failure
                destination.write_text(json.dumps(result, indent=2) + '\n')
            if status != 'Success':
                raise RuntimeError('Qualification failed; inspect ' + str(destination))
            return result
        time.sleep(10)
    aws('ssm', 'cancel-command', '--command-id', command, '--instance-ids', identity)
    raise TimeoutError('Qualification exceeded its bounded execution window')
