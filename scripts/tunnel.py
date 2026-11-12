#!/usr/bin/env python3
"""Open the official authenticated SSM port-forwarding session; no inbound port is needed."""
import argparse,subprocess
from aws import aws,verify,environment,STACK,REGION
parser=argparse.ArgumentParser();parser.add_argument('--port',type=int,default=8080);args=parser.parse_args()
if not 1024<=args.port<=65535:parser.error('Choose a local port in 1024..65535')
verify()
outputs={x['OutputKey']:x['OutputValue'] for x in aws('cloudformation','describe-stacks','--stack-name',STACK)['Stacks'][0]['Outputs']}
subprocess.run(['aws','ssm','start-session','--target',outputs['Instance'],'--document-name','AWS-StartPortForwardingSession',
    '--parameters',f'portNumber=8080,localPortNumber={args.port}','--region',REGION],env=environment(),check=True)
