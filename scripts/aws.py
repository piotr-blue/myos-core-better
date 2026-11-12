#!/usr/bin/env python3
"""Explicit AWS account/region boundary; never print or persist secret credentials."""
import json, os, subprocess
from pathlib import Path

ACCOUNT = '581571886327'
REGION = 'eu-west-1'
STACK = os.environ.get('MYOS_STACK', 'myos-better-canonical-20261108')

def environment():
    env = os.environ.copy()
    selected = os.environ.get('MYOS_AWS_PROPERTIES')
    if selected:
        values = dict(line.strip().split('=', 1) for line in Path(selected).read_text().splitlines()
                      if '=' in line and not line.lstrip().startswith(('#', '!')))
        env['AWS_ACCESS_KEY_ID'] = values['access'].strip()
        env['AWS_SECRET_ACCESS_KEY'] = values['secret'].strip()
        if values.get('token'): env['AWS_SESSION_TOKEN'] = values['token'].strip()
        else: env.pop('AWS_SESSION_TOKEN', None)
        env.pop('AWS_SECURITY_TOKEN', None)
    env.update(AWS_DEFAULT_REGION=REGION, AWS_REGION=REGION, AWS_PAGER='')
    return env

def aws(*args):
    run = subprocess.run(['aws', *args, '--region', REGION, '--output', 'json'],
                         env=environment(), capture_output=True, text=True, check=True)
    return json.loads(run.stdout) if run.stdout.strip() else None

def verify():
    identity = aws('sts', 'get-caller-identity')
    if identity['Account'] != ACCOUNT:
        raise RuntimeError('Wrong AWS account; refusing to act')
    return {'account': ACCOUNT, 'region': REGION, 'stack': STACK}

if __name__ == '__main__':
    print(json.dumps(verify()))
