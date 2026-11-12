#!/usr/bin/env python3
"""Inspect one experiment command using metadata emitted by deploy.py or run_benchmark.py."""
import argparse, json
from pathlib import Path
from aws import aws, verify, STACK

parser = argparse.ArgumentParser()
parser.add_argument('metadata', type=Path, help='JSON command metadata saved by a deployment or benchmark')
args = parser.parse_args()
verify()
outputs = {entry['OutputKey']: entry['OutputValue'] for entry in
           aws('cloudformation', 'describe-stacks', '--stack-name', STACK)['Stacks'][0]['Outputs']}
metadata = json.loads(args.metadata.read_text())
command = metadata.get('Command', metadata)
identifier = command.get('CommandId', command.get('commandId'))
instances = command.get('InstanceIds', [command.get('instance', outputs['Instance'])])
if not identifier or instances != [outputs['Instance']]:
    parser.error('Metadata must identify one command on this experiment instance')
result = aws('ssm', 'get-command-invocation', '--command-id', identifier,
             '--instance-id', outputs['Instance'])
print(json.dumps({key: result[key] for key in
                 ('CommandId', 'Status', 'ResponseCode', 'StandardOutputContent', 'StandardErrorContent')
                 if key in result}, indent=2))
