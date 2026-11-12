"""Retained callback-envelope key: public version pins, no provider-key reuse."""
import re
from aws import ACCOUNT, REGION
from provider_infra import ROLE

STACK = 'myos-better-webhooks-20261107'


def template():
    return {
        'AWSTemplateFormatVersion': '2010-09-09',
        'Description': 'Retained callback encryption key for the isolated Better dashboard.',
        'Resources': {
            'Identity': {'Type': 'AWS::SecretsManager::Secret', 'DeletionPolicy': 'Retain',
                         'UpdateReplacePolicy': 'Retain', 'Properties': {
                             'Description': 'Pinned callback envelope key; rotation requires re-encryption',
                             'GenerateSecretString': {
                                 'SecretStringTemplate': '{"format":"myos.better.webhook-key/1"}',
                                 'GenerateStringKey': 'seed', 'PasswordLength': 64,
                                 'ExcludePunctuation': True, 'IncludeSpace': False},
                             'Tags': [{'Key': 'owner', 'Value': 'myos-core-better'},
                                      {'Key': 'environment', 'Value': 'test'},
                                      {'Key': 'purpose', 'Value': 'dashboard-parity'}]}},
            'ReadIdentity': {'Type': 'AWS::IAM::Policy', 'Properties': {
                'PolicyName': STACK + '-read', 'Roles': [ROLE],
                'PolicyDocument': {'Version': '2012-10-17', 'Statement': [{
                    'Effect': 'Allow', 'Action': ['secretsmanager:GetSecretValue'],
                    'Resource': {'Ref': 'Identity'}}]}}}},
        'Outputs': {'SecretArn': {'Value': {'Ref': 'Identity'}}}}


def validate(value):
    if set(value) != {'secretArn', 'secretVersion'}:
        raise ValueError('Invalid callback secret configuration fields')
    if not re.fullmatch(f'arn:aws:secretsmanager:{REGION}:{ACCOUNT}:secret:[A-Za-z0-9/_+=.@-]+', value['secretArn']):
        raise ValueError('Callback secret must belong to the authorized AWS account and region')
    if not re.fullmatch(r'[A-Za-z0-9-]{32,64}', value['secretVersion']):
        raise ValueError('Callback secret version must be pinned')
    return value


def environment(value):
    value = validate(value)
    return ('Environment=MYOS_WEBHOOK_SECRET_ARN=' + value['secretArn'] + '\n'
            + 'Environment=MYOS_WEBHOOK_SECRET_VERSION=' + value['secretVersion'] + '\n')
