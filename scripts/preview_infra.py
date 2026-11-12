#!/usr/bin/env python3
"""Prepare or create a separate canonical preview, retaining the existing console and histories."""
import argparse
import copy
import json
from pathlib import Path

from aws import ACCOUNT, REGION, aws, verify
from console_infra import STACK as EXISTING_CONSOLE

STACK = 'myos-better-canonical-20261108'
CONSOLE = 'myos-better-canonical-console-20261108'
CUSTODY = 'myos-better-canonical-custody-20261108'
READER_PREFIX = 'myos-better-reader-canonical-v1-'


def core_template(base):
    result = copy.deepcopy(base)
    result['Description'] = 'Isolated canonical Blue preview: one host, protected DynamoDB records and scoped signing custody.'
    resources = result['Resources']
    resources['Documents']['Properties']['TableName'] = STACK + '-documents'
    for resource in resources.values():
        for tag in resource.get('Properties', {}).get('Tags', []):
            if tag['Key'] in ('Name', 'project'):
                tag['Value'] = STACK
    resources['ProviderIdentity'] = {
        'Type': 'AWS::SecretsManager::Secret', 'DeletionPolicy': 'Retain', 'UpdateReplacePolicy': 'Retain',
        'Properties': {'Description': 'Canonical preview provider identity; immutable pinned version',
                       'Tags': [{'Key': 'owner', 'Value': 'myos-core-better'}, {'Key': 'project', 'Value': STACK}]}}
    resources['Role']['Properties']['Policies'].append({
        'PolicyName': 'CanonicalPreviewCustody', 'PolicyDocument': {
            'Version': '2012-10-17', 'Statement': [
                {'Effect': 'Allow', 'Action': ['secretsmanager:GetSecretValue'],
                 'Resource': {'Ref': 'ProviderIdentity'}},
                {'Effect': 'Allow', 'Action': ['secretsmanager:GetSecretValue'],
                 'Resource': f'arn:aws:secretsmanager:{REGION}:{ACCOUNT}:secret:{READER_PREFIX}*'},
                {'Effect': 'Allow', 'Action': ['secretsmanager:CreateSecret'],
                 'Resource': f'arn:aws:secretsmanager:{REGION}:{ACCOUNT}:secret:{READER_PREFIX}*',
                 'Condition': {'StringLike': {'secretsmanager:Name': READER_PREFIX + '*'}}}]}})
    result['Outputs']['SecretArn'] = {'Value': {'Ref': 'ProviderIdentity'}}
    return result


def edge_template(base, target, identities):
    result = copy.deepcopy(base)
    result['Description'] = 'Canonical preview HTTPS edge; existing sign-in identities, isolated application storage.'
    resources = result['Resources']
    del resources['UserPool']
    del resources['UserClient']
    resources['ConsoleIngress']['Properties']['GroupId'] = target['securityGroup']
    origin = resources['VpcOrigin']['Properties']['VpcOriginEndpointConfig']
    origin.update(Name=CONSOLE, Arn=f"arn:aws:ec2:{REGION}:{ACCOUNT}:instance/{target['instance']}")
    distribution = resources['Distribution']['Properties']['DistributionConfig']
    distribution['Comment'] = 'MyOS canonical preview — isolated application storage'
    distribution['Origins'][0]['DomainName'] = target['privateDns']
    result['Outputs']['UserPool'] = {'Value': identities['UserPool']}
    result['Outputs']['UserClient'] = {'Value': identities['UserClient']}
    return result


def outputs(stack):
    value = aws('cloudformation', 'describe-stacks', '--stack-name', stack)['Stacks'][0]
    if value['StackStatus'] not in ('CREATE_COMPLETE', 'UPDATE_COMPLETE'):
        raise RuntimeError('Stack is not ready: ' + value['StackStatus'])
    return {row['OutputKey']: row['OutputValue'] for row in value['Outputs']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('component', choices=('core', 'edge', 'custody'))
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    verify()
    root = Path(__file__).resolve().parents[1]
    if args.component == 'core':
        stack = STACK
        body = core_template(json.loads((root / 'infra/stack.json').read_text()))
        # Resolve the current public AMI explicitly, rather than assuming an old rendered ID.
        body['Resources']['Host']['Properties']['ImageId'] = aws(
            'ssm', 'get-parameter', '--name',
            '/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64')['Parameter']['Value']
    elif args.component == 'edge':
        stack = CONSOLE
        selected = outputs(STACK)
        host = aws('ec2', 'describe-instances', '--instance-ids', selected['Instance'])['Reservations'][0]['Instances'][0]
        if host['State']['Name'] != 'running' or len(host['SecurityGroups']) != 1:
            raise RuntimeError('Unexpected canonical preview host')
        target = {'instance': selected['Instance'], 'securityGroup': host['SecurityGroups'][0]['GroupId'],
                  'privateDns': host['PrivateDnsName']}
        body = edge_template(json.loads((root / 'infra/console-stack.json').read_text()),
                             target, outputs(EXISTING_CONSOLE))
    else:
        import webhook_infra
        stack = CUSTODY
        outputs(STACK)
        role = aws('cloudformation', 'describe-stack-resource', '--stack-name', STACK,
                   '--logical-resource-id', 'Role')['StackResourceDetail']['PhysicalResourceId']
        body = webhook_infra.template()
        body['Description'] = 'Canonical preview dashboard envelope key; separate from provider signing.'
        body['Resources']['ReadIdentity']['Properties']['Roles'] = [role]
        body['Resources']['ReadIdentity']['Properties']['PolicyName'] = CUSTODY + '-read'
    path = root / 'infra' / ('canonical-preview-' + args.component + '.json')
    path.write_text(json.dumps(body, indent=2) + '\n')
    aws('cloudformation', 'validate-template', '--template-body', 'file://' + str(path))
    if args.apply:
        result = aws('cloudformation', 'create-stack', '--stack-name', stack,
                     '--template-body', 'file://' + str(path), '--capabilities', 'CAPABILITY_IAM',
                     '--tags', 'Key=owner,Value=myos-core-better', 'Key=project,Value=' + STACK)
        print(json.dumps({'stack': stack, 'submitted': result}))
    else:
        print(json.dumps({'stack': stack, 'template': str(path), 'validated': True}))


if __name__ == '__main__':
    main()
