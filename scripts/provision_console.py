#!/usr/bin/env python3
"""Submit the isolated console stack once; polling is a separate, bounded read."""
import json
from pathlib import Path
from aws import aws, verify
from console_infra import STACK, INSTANCE, template

root = Path(__file__).resolve().parents[1]
verify()
instance = aws('ec2', 'describe-instances', '--instance-ids', INSTANCE)['Reservations'][0]['Instances'][0]
if instance['State']['Name'] != 'running': raise RuntimeError('Better host must be running')
prefix = aws('ec2', 'describe-managed-prefix-lists', '--filters', 'Name=prefix-list-name,Values=com.amazonaws.global.cloudfront.origin-facing')['PrefixLists'][0]
target = {'instance': INSTANCE, 'securityGroups': [group['GroupId'] for group in instance['SecurityGroups']],
          'privateDns': instance['PrivateDnsName'], 'prefixList': prefix['PrefixListId']}
body = template(target)
(root/'infra').mkdir(exist_ok=True)
path = root/'infra/console-stack.json'
path.write_text(json.dumps(body, indent=2)+'\n')
aws('cloudformation', 'validate-template', '--template-body', 'file://'+str(path))
result = aws('cloudformation', 'create-stack', '--stack-name', STACK, '--template-body', 'file://'+str(path),
             '--tags', 'Key=owner,Value=myos-core-better', 'Key=review-after,Value=2026-11-12')
(root/'reports').mkdir(exist_ok=True)
(root/'reports/console-stack.json').write_text(json.dumps(result, indent=2)+'\n')
print(json.dumps(result))
