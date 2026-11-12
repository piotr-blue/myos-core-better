"""Persistent provider key custody; public deployment pins contain no private key."""
import re
from aws import ACCOUNT, REGION

STACK = 'myos-better-provider-20261106'
ROLE = 'myos-better-20261105-Role-7kYPckr6sBe1'


def template():
    return {
        'AWSTemplateFormatVersion': '2010-09-09',
        'Description': 'Retained signing identity for the isolated Better owner Timeline console.',
        'Resources': {
            'Identity': {'Type': 'AWS::SecretsManager::Secret', 'DeletionPolicy': 'Retain',
                         'UpdateReplacePolicy': 'Retain', 'Properties': {
                             'Description': 'Pinned Ed25519 provider identity; no automatic key replacement',
                             'Tags': [{'Key': 'owner', 'Value': 'myos-core-better'}]}},
            'ReadIdentity': {'Type': 'AWS::IAM::Policy', 'Properties': {
                'PolicyName': STACK + '-read', 'Roles': [ROLE],
                'PolicyDocument': {'Version': '2012-10-17', 'Statement': [{
                    'Effect': 'Allow', 'Action': ['secretsmanager:GetSecretValue'],
                    'Resource': {'Ref': 'Identity'}}]}}},
        },
        'Outputs': {'SecretArn': {'Value': {'Ref': 'Identity'}}},
    }


def validate(value, origin):
    if set(value) != {'secretArn', 'secretVersion', 'providerId', 'endpoint', 'namespace'}:
        raise ValueError('Invalid public provider configuration fields')
    if not re.fullmatch(f'arn:aws:secretsmanager:{REGION}:{ACCOUNT}:secret:[A-Za-z0-9/_+=.@-]+', value['secretArn']):
        raise ValueError('Provider secret must belong to the isolated AWS account and region')
    if not re.fullmatch(r'[A-Za-z0-9-]{32,64}', value['secretVersion']):
        raise ValueError('Provider secret version must be pinned')
    if not re.fullmatch(r'[1-9A-HJ-NP-Za-km-z]{43,44}', value['providerId']):
        raise ValueError('Expected exact provider BlueId')
    if value['endpoint'] != origin + '/api/provider' or value['namespace'] != 'console-timelines':
        raise ValueError('Provider endpoint or namespace changed; migration is required')
    return value


def environment(value, origin):
    value = validate(value, origin)
    return ''.join('Environment=' + key + '=' + value[field] + '\n' for key, field in [
        ('MYOS_PROVIDER_SECRET_ARN', 'secretArn'), ('MYOS_PROVIDER_SECRET_VERSION', 'secretVersion'),
        ('MYOS_PROVIDER_ID', 'providerId')])
