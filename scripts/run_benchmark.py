#!/usr/bin/env python3
"""Run a reproducible synthetic workload on the isolated instance; never selects another stack."""
import argparse,json,re,shlex,uuid
from pathlib import Path
from aws import aws,verify,STACK
import deployment_runtime

parser=argparse.ArgumentParser()
parser.add_argument('--orders',type=int,required=True)
parser.add_argument('--payments',type=int,default=3)
parser.add_argument('--operations',type=int,default=128)
parser.add_argument('--account',required=True)
mode_args=parser.add_mutually_exclusive_group()
mode_args.add_argument('--probe',action='store_true')
mode_args.add_argument('--prepare-only',action='store_true')
parser.add_argument('--active-orders',type=int)
parser.add_argument('--stored-links',action='store_true',help='Sample actual stored order links, including non-sequential growth keys')
parser.add_argument('--separate-acks',action='store_true',help='Keep source acknowledgment separate for a same-artifact comparison')
args=parser.parse_args()
if not re.fullmatch(r'bench-[A-Za-z0-9._-]{1,90}',args.account): parser.error('A synthetic bench-* account is required')
if not (1<=args.orders<=100000 and 1<=args.payments<=8 and 1<=args.operations<=10000): parser.error('Invalid workload dimensions')
if args.stored_links and not args.probe: parser.error('--stored-links requires --probe')
verify()
outputs={x['OutputKey']:x['OutputValue'] for x in aws('cloudformation','describe-stacks','--stack-name',STACK)['Stacks'][0]['Outputs']}
reports=Path(__file__).resolve().parents[1]/'reports'
deployment=json.loads((reports/'deployment.json').read_text())
if any(deployment[key]!=outputs[name] for key,name in [('instance','Instance'),('table','Table'),('bucket','Artifacts')]):
    raise RuntimeError('Deployment metadata does not belong to this experiment stack')
status=aws('ssm','get-command-invocation','--command-id',deployment['commandId'],'--instance-id',deployment['instance'])
if status['Status']!='Success': raise RuntimeError('A healthy deployment is required')
release='/opt/myos-better/releases/'+deployment['artifactSha256']
if args.active_orders is not None and (not args.probe or not 1<=args.active_orders<=args.orders): parser.error('--active-orders requires --probe and must be within --orders')
mode=('probe-links' if args.stored_links else 'probe') if args.probe else 'populate' if args.prepare_only else 'benchmark'
name=deployment['artifactSha256'][:12]+'-'+mode+'-'+uuid.uuid4().hex[:8]
log=f'/var/lib/myos-better/{name}.jsonl'
process=['sudo','-u','myos-better','env','MYOS_TABLE='+outputs['Table'],'AWS_REGION=eu-west-1',
    'MYOS_COMBINE_ACKS='+('false' if args.separate_acks else 'true'),
    'JAVA_OPTS=-Xms256m -Xmx1g',release+'/bin/myos-core-better',mode,str(args.orders),str(args.payments),str(args.operations),args.account]
if args.active_orders is not None: process.append(str(args.active_orders))
profile_name=deployment.get('runtimeProfile', 'canonical')
profile=deployment_runtime.settings(profile_name)
if deployment.get('edition', profile['edition']) != profile['edition']:
    raise RuntimeError('Inconsistent deployment edition metadata')
if profile['manifest'] is not None:
    process.insert(process.index('JAVA_OPTS=-Xms256m -Xmx1g'), 'MYOS_RUNTIME='+release+'/'+profile['manifest'])
command=shlex.join(process)
restore='''restore() {
  result=$?
  trap - EXIT
  systemctl start myos-better.service || result=1
  healthy=0
  for i in $(seq 1 30); do
    if curl --fail --silent http://127.0.0.1:8080/health; then healthy=1; break; fi
    sleep 1
  done
  if [ "$healthy" -ne 1 ]; then result=1; fi
  exit "$result"
}
trap restore EXIT'''
restore=restore.replace('curl --fail --silent http://127.0.0.1:8080/health', deployment_runtime.health_check(profile_name))
summary='''import json
value=json.loads(open(FILE).read().strip().splitlines()[-1])
print(json.dumps({key:value[key] for key in ['phase','orders','documents','documentEdition','setupMillis','activeOrders','operations',
    'localPaymentMedianMillis','leafCommitMedianMillis','endToEndMedianMillis','paidTotal'] if key in value}))
'''.replace('FILE',repr(log))
commands=['set -euo pipefail','exec 9>/var/lock/myos-better-deploy.lock; flock -n 9',
    'test "$(readlink -f /opt/myos-better/current)" = '+shlex.quote(release), restore,
    'systemctl stop myos-better.service',command+' > '+shlex.quote(log)+' 2>&1',
    shlex.join(['aws','s3','cp',log,'s3://'+outputs['Artifacts']+'/reports/'+name+'.jsonl','--region','eu-west-1','--only-show-errors']),
    "python3 - <<'MYOS_SUMMARY'\n"+summary+'MYOS_SUMMARY']
request={'DocumentName':'AWS-RunShellScript','InstanceIds':[outputs['Instance']],
    'Parameters':{'commands':commands,'executionTimeout':['3600' if args.probe else '21600']},'Comment':'Pinned synthetic MyOS scale qualification with service recovery'}
reports=Path(__file__).resolve().parents[1]/'reports';reports.mkdir(exist_ok=True)
p=reports/(name+'-request.json');p.write_text(json.dumps(request))
r=aws('ssm','send-command','--cli-input-json','file://'+str(p))
(reports/(name+'-command.json')).write_text(json.dumps(r,indent=2))
metadata={'commandId':r['Command']['CommandId'],'instance':outputs['Instance'],'key':'reports/'+name+'.jsonl',
    'artifactSha256':deployment['artifactSha256'],'account':args.account,'orders':args.orders,'payments':args.payments,
    'operations':0 if args.prepare_only else args.operations,'activeOrders':args.active_orders,'mode':mode,
    'combinedAcknowledgements':not args.separate_acks,'creationEdition':profile['edition']}
(reports/'benchmark-latest-command.json').write_text(json.dumps(metadata,indent=2)+'\n')
print(json.dumps(metadata))
