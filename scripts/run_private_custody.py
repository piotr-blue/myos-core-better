#!/usr/bin/env python3
"""Bounded private-reader AWS qualification with one temporary secret and exact-scope IAM cleanup."""
import argparse
import hashlib
import json
import re
import subprocess
import uuid
from datetime import datetime, timezone
from pathlib import Path

from aws import aws, verify, ACCOUNT, REGION, STACK
from run_native_admission import check_deployment, commands

TERMINAL = {'Success', 'Failed', 'TimedOut', 'Cancelled'}
DESCRIPTION = 'Ephemeral Better private-reader qualification'


def scope(identifier):
    if not re.fullmatch(r'[a-f0-9]{32}', identifier):
        raise ValueError('A fresh hexadecimal qualification ID is required')
    return {'account': 'native-bench-' + identifier,
            'secretName': 'myos-better-qualification-reader-' + identifier,
            'policyName': 'PrivateReaderQualification-' + identifier}


def policy(identifier):
    selected = scope(identifier)
    resource = f'arn:aws:secretsmanager:{REGION}:{ACCOUNT}:secret:{selected["secretName"]}-??????'
    return {'Version': '2012-10-17', 'Statement': [
        {'Effect': 'Allow', 'Action': ['secretsmanager:CreateSecret'], 'Resource': '*',
         'Condition': {'StringEquals': {'secretsmanager:Name': selected['secretName'],
                                      'secretsmanager:Description': DESCRIPTION,
                                      'aws:RequestedRegion': REGION}}},
        {'Effect': 'Allow', 'Action': ['secretsmanager:GetSecretValue', 'secretsmanager:DeleteSecret'],
         'Resource': resource}]}


def missing(error, code):
    return isinstance(error, subprocess.CalledProcessError) and f'({code})' in (error.stderr or '')


def secret(name):
    try:
        return aws('secretsmanager', 'describe-secret', '--secret-id', name)
    except subprocess.CalledProcessError as error:
        if missing(error, 'ResourceNotFoundException'):
            return None
        raise


def write(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


def validate(record):
    selected = scope(record['identifier'])
    if any(record[k] != v for k, v in selected.items()):
        raise ValueError('Qualification resource scope mismatch')
    if record['accountId'] != ACCOUNT or record['region'] != REGION or record['stack'] != STACK:
        raise ValueError('Qualification belongs to another AWS scope')
    if not re.fullmatch(re.escape(STACK) + r'-Role-[A-Za-z0-9]+', record['role']):
        raise ValueError('Qualification role is outside the isolated stack')
    if record['policy'] != policy(record['identifier']):
        raise ValueError('Qualification policy changed')


def submit():
    verify()
    root = Path(__file__).resolve().parents[1]
    reports = root / 'reports'
    deployment = json.loads((reports / 'deployment.json').read_text())
    outputs = {v['OutputKey']: v['OutputValue'] for v in
               aws('cloudformation', 'describe-stacks', '--stack-name', STACK)['Stacks'][0]['Outputs']}
    status = aws('ssm', 'get-command-invocation', '--command-id', deployment['commandId'],
                 '--instance-id', deployment['instance'])
    check_deployment(deployment, outputs, status['Status'])
    detail = aws('cloudformation', 'describe-stack-resource', '--stack-name', STACK,
                 '--logical-resource-id', 'Role')['StackResourceDetail']
    if detail['ResourceType'] != 'AWS::IAM::Role':
        raise RuntimeError('Expected the existing host role')
    identifier = uuid.uuid4().hex
    selected = scope(identifier)
    tool, = (root / 'build/libs').glob('*-qualification.jar')
    tool_digest = hashlib.sha256(tool.read_bytes()).hexdigest()
    runtime = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in
               (root / 'build/install/myos-core-better/lib').glob('*.jar')}
    name = 'private-custody-' + identifier
    generated = commands(deployment, tool_digest, runtime, name, selected['account'], 0, 1,
                         'private-directory', selected['secretName'])
    # IAM propagation may lag its successful write; wait before stopping the ordinary service.
    generated.insert(2, 'sleep 15')
    request = {'DocumentName': 'AWS-RunShellScript', 'InstanceIds': [deployment['instance']],
               'Parameters': {'commands': generated, 'executionTimeout': ['360']},
               'Comment': 'Private reader custody qualification ' + identifier}
    request_path = reports / (name + '-request.json')
    policy_path = reports / (name + '-policy.json')
    metadata = reports / (name + '-command.json')
    record = dict(selected, identifier=identifier, accountId=ACCOUNT, region=REGION, stack=STACK,
                  role=detail['PhysicalResourceId'], policy=policy(identifier),
                  artifactSha256=deployment['artifactSha256'], runtimeArtifacts=runtime,
                  toolSha256=tool_digest, instance=deployment['instance'], bucket=deployment['bucket'],
                  key='reports/' + name + '.jsonl', requestFile=str(request_path),
                  createdAt=datetime.now(timezone.utc).isoformat(), comment=request['Comment'],
                  submissionStarted=False)
    validate(record)
    if secret(selected['secretName']) is not None:
        raise RuntimeError('Refusing to reuse an existing secret')
    if selected['policyName'] in aws('iam', 'list-role-policies', '--role-name', record['role'])['PolicyNames']:
        raise RuntimeError('Refusing to replace an existing role policy')
    write(request_path, request); write(policy_path, record['policy'])
    with metadata.open('x') as output:
        output.write(json.dumps(record, indent=2) + '\n')
    print(json.dumps({'metadata': str(metadata), 'phase': 'PREPARED'}), flush=True)
    aws('s3api', 'put-object', '--bucket', deployment['bucket'],
        '--key', 'releases/tools/qualification-' + tool_digest + '.jar', '--body', str(tool))
    aws('iam', 'put-role-policy', '--role-name', record['role'], '--policy-name', selected['policyName'],
        '--policy-document', 'file://' + str(policy_path))
    record['policyInstalled'] = True; write(metadata, record)
    record['submissionStarted'] = True; write(metadata, record)
    record['commandId'] = aws('ssm', 'send-command', '--cli-input-json', 'file://' + str(request_path))['Command']['CommandId']
    write(metadata, record)
    write(reports / 'private-custody-latest-command.json', record)
    print(json.dumps({'metadata': str(metadata), 'commandId': record['commandId'], 'secretName': selected['secretName']}), flush=True)


def command(record, path):
    if not record.get('commandId') and record.get('submissionStarted'):
        # A lost submit response is not permission to submit again or revoke a live job's custody.
        matches = [c for c in aws('ssm', 'list-commands', '--filters',
                   json.dumps([{'key': 'InvokedAfter', 'value': record['createdAt']}]))['Commands']
                   if c.get('Comment') == record['comment'] and c.get('InstanceIds') == [record['instance']]]
        if len(matches) != 1:
            raise RuntimeError('SSM submission is unresolved; do not restart or clean up yet')
        record['commandId'] = matches[0]['CommandId']; write(path, record)
    if not record.get('commandId'):
        return None
    return aws('ssm', 'get-command-invocation', '--command-id', record['commandId'],
               '--instance-id', record['instance'])


def cleanup(record, path):
    validate(record)
    state = command(record, path)
    if state is not None and state['Status'] not in TERMINAL:
        raise RuntimeError('Qualification still live; retain its custody and poll the same command')
    current = secret(record['secretName'])
    if current is not None:
        prefix = f'arn:aws:secretsmanager:{REGION}:{ACCOUNT}:secret:{record["secretName"]}-'
        if (current['Name'] != record['secretName'] or not re.fullmatch(re.escape(prefix) + r'[A-Za-z0-9]{6}', current['ARN'])
                or current.get('Description') != DESCRIPTION):
            raise RuntimeError('Refusing to remove a secret with different ownership metadata')
    actual = None
    try:
        actual = aws('iam', 'get-role-policy', '--role-name', record['role'], '--policy-name', record['policyName'])
    except subprocess.CalledProcessError as error:
        if not missing(error, 'NoSuchEntity'):
            raise
    else:
        if actual['PolicyDocument'] != record['policy']:
            raise RuntimeError('Refusing to remove a role policy changed by another actor')
    if current is not None and not current.get('DeletedDate'):
        aws('secretsmanager', 'delete-secret', '--secret-id', current['ARN'], '--force-delete-without-recovery')
    if actual is not None:
        aws('iam', 'delete-role-policy', '--role-name', record['role'], '--policy-name', record['policyName'])
    record['cleanup'] = {'temporarySecretDeletionAcceptedOrAbsent': True,
                         'temporaryPolicyRemovedOrAbsent': True,
                         'recordedAt': datetime.now(timezone.utc).isoformat()}
    write(path, record)
    return record['cleanup']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['submit', 'status', 'cleanup'])
    parser.add_argument('metadata', nargs='?', type=Path)
    args = parser.parse_args()
    if args.action == 'submit':
        if args.metadata is not None: parser.error('Submission creates its own new metadata')
        submit(); return
    if args.metadata is None: parser.error('An existing qualification metadata file is required')
    verify(); record = json.loads(args.metadata.read_text()); validate(record)
    if args.action == 'cleanup':
        print(json.dumps(cleanup(record, args.metadata))); return
    result = command(record, args.metadata)
    if result is None:
        print(json.dumps({'status': 'NOT_SUBMITTED'})); return
    write(args.metadata.with_name(args.metadata.stem.replace('-command', '-status') + '.json'), result)
    print(json.dumps({k: result[k] for k in ['CommandId', 'Status', 'ResponseCode']}))


if __name__ == '__main__':
    main()
