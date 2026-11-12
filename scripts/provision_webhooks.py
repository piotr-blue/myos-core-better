#!/usr/bin/env python3
"""Provision/pin one retained callback key. AWS generates it; this tool never reads key material."""
import argparse
import json
from pathlib import Path
from aws import aws, verify
import webhook_infra


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--custody-stack', default=webhook_infra.STACK)
    parser.add_argument('--manifest', default='infra/webhook-secret.json')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    reports = root / 'reports'
    reports.mkdir(exist_ok=True)
    verify()
    manifest = root / args.manifest
    if manifest.exists():
        pin = webhook_infra.validate(json.loads(manifest.read_text()))
        versions = aws('secretsmanager', 'list-secret-version-ids', '--secret-id', pin['secretArn'])['Versions']
        if not any(v['VersionId'] == pin['secretVersion'] for v in versions):
            raise RuntimeError('Pinned callback key is unavailable; refusing replacement')
        print(json.dumps({'status': 'existing-pinned-callback-key'}))
        return
    stacks = aws('cloudformation', 'list-stacks')['StackSummaries']
    if not any(s['StackName'] == args.custody_stack and s['StackStatus'] != 'DELETE_COMPLETE' for s in stacks):
        if args.custody_stack != webhook_infra.STACK:
            raise RuntimeError('Explicit custody stack must already exist')
        template = reports / 'webhook-template.json'
        template.write_text(json.dumps(webhook_infra.template()))
        aws('cloudformation', 'create-stack', '--stack-name', args.custody_stack,
            '--template-body', 'file://' + str(template), '--capabilities', 'CAPABILITY_NAMED_IAM')
        print('Callback custody stack submitted. Run again after CREATE_COMPLETE.')
        return
    stack = aws('cloudformation', 'describe-stacks', '--stack-name', args.custody_stack)['Stacks'][0]
    if stack['StackStatus'] not in {'CREATE_COMPLETE', 'UPDATE_COMPLETE'}:
        raise RuntimeError('Callback custody stack is not ready: ' + stack['StackStatus'])
    arn = next(v['OutputValue'] for v in stack['Outputs'] if v['OutputKey'] == 'SecretArn')
    versions = aws('secretsmanager', 'list-secret-version-ids', '--secret-id', arn).get('Versions', [])
    if len(versions) != 1 or 'AWSCURRENT' not in versions[0].get('VersionStages', []):
        raise RuntimeError('Ambiguous existing callback versions; refusing to guess a master key')
    pin = webhook_infra.validate(dict(secretArn=arn, secretVersion=versions[0]['VersionId']))
    manifest.write_text(json.dumps(pin, indent=2) + '\n')
    print(json.dumps({'status': 'callback-key-pinned'}))


if __name__ == '__main__':
    main()
