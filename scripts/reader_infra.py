"""Immutable reader custody for this isolated deployment; no provider-key permissions."""
from aws import ACCOUNT, REGION
from provider_infra import ROLE

STACK = 'myos-better-readers-20261106'
PREFIX = 'myos-better-reader-console-v1-'


def template():
    resource = f'arn:aws:secretsmanager:{REGION}:{ACCOUNT}:secret:{PREFIX}*'
    return {
        'AWSTemplateFormatVersion': '2010-09-09',
        'Description': 'Create and read immutable Better per-owner Timeline reader identities.',
        'Resources': {'ReaderCustody': {'Type': 'AWS::IAM::Policy', 'Properties': {
            'PolicyName': STACK, 'Roles': [ROLE],
            'PolicyDocument': {'Version': '2012-10-17', 'Statement': [
                {'Effect': 'Allow', 'Action': ['secretsmanager:GetSecretValue'], 'Resource': resource},
                {'Effect': 'Allow', 'Action': ['secretsmanager:CreateSecret'], 'Resource': resource,
                 'Condition': {'StringLike': {'secretsmanager:Name': PREFIX + '*'}}},
            ]}}}},
    }


def environment(prefix=None):
    import re
    selected = PREFIX if prefix is None else prefix
    if not re.fullmatch(r'myos-better-reader-[a-z0-9-]{1,64}-', selected):
        raise ValueError('Invalid explicit reader custody prefix')
    return f'Environment=MYOS_READER_SECRET_PREFIX={selected}\nEnvironment=MYOS_READER_AWS_ACCOUNT={ACCOUNT}\n'


if __name__ == '__main__':
    import argparse
    import json
    from pathlib import Path
    from aws import aws, verify
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    value = template()
    if not args.apply:
        print(json.dumps(value, indent=2))
    else:
        verify()
        report = Path(__file__).resolve().parents[1] / 'reports/reader-custody-template.json'
        report.write_text(json.dumps(value, indent=2) + '\n')
        # This stack owns only the scoped policy; it never replaces or deletes reader identities.
        stacks = aws('cloudformation', 'list-stacks', '--stack-status-filter', 'CREATE_COMPLETE', 'UPDATE_COMPLETE', 'ROLLBACK_COMPLETE')['StackSummaries']
        action = 'update-stack' if any(s['StackName'] == STACK for s in stacks) else 'create-stack'
        result = aws('cloudformation', action, '--stack-name', STACK, '--template-body', 'file://' + str(report), '--capabilities', 'CAPABILITY_NAMED_IAM')
        print(json.dumps({'stack': STACK, 'result': result}))
