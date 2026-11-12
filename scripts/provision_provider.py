#!/usr/bin/env python3
"""Explicit first-time key setup. Retains the AWS secret and never replaces an existing identity."""
import json
import argparse
import os
import subprocess
import tempfile
import uuid
from pathlib import Path
from aws import aws, verify
from console_infra import STACK as CONSOLE_STACK
import provider_infra


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--console-stack', default=CONSOLE_STACK)
    parser.add_argument('--custody-stack', default=provider_infra.STACK)
    parser.add_argument('--manifest', default='infra/hosted-provider.json')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    reports = root / 'reports'
    reports.mkdir(exist_ok=True)
    verify()
    origin = next(x['OutputValue'] for x in aws('cloudformation', 'describe-stacks', '--stack-name', args.console_stack)['Stacks'][0]['Outputs'] if x['OutputKey'] == 'Url')
    manifest = root / args.manifest
    if manifest.exists():
        config = provider_infra.validate(json.loads(manifest.read_text()), origin)
        versions = aws('secretsmanager', 'list-secret-version-ids', '--secret-id', config['secretArn'])['Versions']
        if not any(x['VersionId'] == config['secretVersion'] for x in versions):
            raise RuntimeError('Pinned provider version is unavailable; refusing replacement')
        print(json.dumps({'status': 'existing-pinned-identity', 'providerId': config['providerId']}))
        return
    stacks = aws('cloudformation', 'list-stacks')['StackSummaries']
    existing = [s for s in stacks if s['StackName'] == args.custody_stack and s['StackStatus'] != 'DELETE_COMPLETE']
    if not existing:
        if args.custody_stack != provider_infra.STACK:
            raise RuntimeError('Provision the selected isolated custody stack first')
        template = reports / 'provider-template.json'
        template.write_text(json.dumps(provider_infra.template()))
        aws('cloudformation', 'create-stack', '--stack-name', provider_infra.STACK, '--template-body', 'file://' + str(template), '--capabilities', 'CAPABILITY_NAMED_IAM')
        print('Provider custody stack submitted. Run again after CREATE_COMPLETE; no key has been generated.')
        return
    stack = aws('cloudformation', 'describe-stacks', '--stack-name', args.custody_stack)['Stacks'][0]
    if stack['StackStatus'] not in {'CREATE_COMPLETE', 'UPDATE_COMPLETE'}:
        raise RuntimeError('Provider custody stack is not ready: ' + stack['StackStatus'])
    arn = next(x['OutputValue'] for x in stack['Outputs'] if x['OutputKey'] == 'SecretArn')
    versions = aws('secretsmanager', 'list-secret-version-ids', '--secret-id', arn).get('Versions', [])
    prepared = reports / ('provider-prepared.json' if args.manifest == 'infra/hosted-provider.json'
                          else manifest.stem + '-prepared.json')
    if versions:
        if not prepared.exists(): raise RuntimeError('Existing identity without public pin; refuse replacement')
        config = provider_infra.validate(json.loads(prepared.read_text()), origin)
        if config['secretArn'] != arn or not any(v['VersionId'] == config['secretVersion'] for v in versions):
            raise RuntimeError('Prepared identity does not match stored version; refuse replacement')
    else:
        with tempfile.TemporaryDirectory(prefix='myos-provider-') as directory:
            path = Path(directory) / 'identity.json'
            classpath = os.pathsep.join([str(root / 'build/classes/java/qualification'), str(root / 'build/install/myos-core-better/lib/*')])
            run = subprocess.run([str(Path(os.environ['JAVA_HOME']) / 'bin/java'), '-cp', classpath,
                                  'myos.better.ProviderIdentityTool', origin + '/api/provider', str(path)], capture_output=True, text=True)
            if run.returncode: raise RuntimeError('Provider key preparation failed; secret diagnostics withheld')
            public = json.loads(run.stdout)
            config = dict(secretArn=arn, secretVersion=str(uuid.uuid4()), providerId=public['providerId'],
                          endpoint=origin + '/api/provider', namespace='console-timelines')
            provider_infra.validate(config, origin)
            prepared.write_text(json.dumps(config, indent=2) + '\n')
            aws('secretsmanager', 'put-secret-value', '--secret-id', arn, '--client-request-token', config['secretVersion'], '--secret-string', 'file://' + str(path))
    manifest.write_text(json.dumps(config, indent=2) + '\n')
    print(json.dumps({'status': 'identity-pinned', 'providerId': config['providerId']}))


if __name__ == '__main__': main()
