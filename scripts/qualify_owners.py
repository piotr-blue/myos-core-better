#!/usr/bin/env python3
"""Submit an isolated independent-owner DynamoDB qualification without changing the live service."""
import argparse,hashlib,json,shlex,tarfile,uuid
from pathlib import Path
from aws import aws,verify,STACK
from qualification_host import worker, wait

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--stack', default=STACK)
    parser.add_argument('--report-dir',type=Path,help='Separate output directory for this qualification run')
    parser.add_argument('--samples', type=int, default=5)
    parser.add_argument('--orders',default='1,128')
    parser.add_argument('--instance',help='Existing dedicated worker; by default use a temporary worker sized for the requested graph')
    parser.add_argument('--profile',action='store_true',help='Capture a bounded JFR of the final payment per size')
    parser.add_argument('--observable-root',action='store_true',help='Emit two equal-valued public Agreement events and verify both ordered occurrences')
    parser.add_argument('--separate-timelines',action='store_true',help='One external timeline per Order; quiet providers certify a prefix beyond the active inputs')
    parser.add_argument('--rotate-targets',action='store_true',help='Select a different Order by a deterministic stride on every input')
    parser.add_argument('--direct-creation', action='store_true', help='Test signed, document-addressed Order creation with an already populated source archive')
    parser.add_argument('--cold',action='store_true',help='Reopen bounded owner storage caches before every input')
    args=parser.parse_args()
    if args.separate_timelines and args.rotate_targets: parser.error('Separate timelines reserve quiet prefixes; rotating targets require the shared timeline')
    if not 1 <= args.samples <= 20: parser.error('Samples must be 1..20')
    counts=[int(v) for v in args.orders.split(',')]
    if not counts or any(v<1 or v>10000 for v in counts):parser.error('Use counts between 1 and 10000')
    large_graph = max(counts) > 1024
    heap_gib, cpu = (8,4) if large_graph else (1,2)
    memory_limit = '12G' if large_graph else '1536M'
    execution_timeout = 5400 if large_graph else 600
    verify()
    root=Path(__file__).resolve().parents[1];reports=args.report_dir or root/'reports';reports.mkdir(parents=True,exist_ok=True)
    outputs={o['OutputKey']:o['OutputValue'] for o in aws('cloudformation','describe-stacks','--stack-name',args.stack)['Stacks'][0]['Outputs']}
    archive=reports/'owner-qualification.tar.gz'
    jars=list((root/'build/libs').glob('*-qualification.jar'))
    if len(jars)!=1:raise RuntimeError('Build installDist qualificationJar first')
    def readable(info):
        info.mode = 0o755 if info.isdir() else 0o644
        return info
    with tarfile.open(archive,'w:gz') as bundle:
        bundle.add(root/'build/install/myos-core-better/lib',arcname='lib',filter=readable)
        bundle.add(jars[0],arcname='qualification.jar',filter=readable)
    digest=hashlib.sha256(archive.read_bytes()).hexdigest();key='releases/owners-qualification-'+digest+'.tgz'
    aws('s3api','put-object','--bucket',outputs['Artifacts'],'--key',key,'--body',str(archive))
    remote='/tmp/myos-owners-'+digest;account='owner-qa-'+uuid.uuid4().hex[:12]
    profile='/tmp/'+account+'-profiles'
    profiling=['-Dblue.probe.profile='+profile] if args.profile else []
    commands=['set -euo pipefail','umask 022','cloud-init status --wait',
        'if systemctl is-active --quiet myos-better || systemctl is-active --quiet myos-native-external; then echo "Refusing to benchmark on a live service host" >&2; exit 1; fi',shlex.join(['mkdir','-p',remote]),
        shlex.join(['aws','s3','cp','s3://'+outputs['Artifacts']+'/'+key,remote+'/bundle.tgz','--region','eu-west-1','--only-show-errors']),
        'echo '+shlex.quote(digest+'  '+remote+'/bundle.tgz')+' | sha256sum --check --status',
        shlex.join(['tar','-xzf',remote+'/bundle.tgz','-C',remote]),
        shlex.join(['install','-d','-m','750','-o','myos-better',profile]),
        shlex.join(['systemd-run','--quiet','--wait','--pipe','--collect','--unit='+account,
            '--uid=myos-better','--property=MemoryMax='+memory_limit,'--property=MemorySwapMax=0',
            f'--property=CPUQuota={cpu*100}%','--setenv=AWS_REGION=eu-west-1','java',f'-Xmx{heap_gib}g',f'-XX:ActiveProcessorCount={cpu}',
            f'-Dblue.probe.expectedHeapBytes={heap_gib * 1024**3}',
            '-Dblue.probe.directAppend='+str(args.direct_creation).lower(), '-Dblue.probe.seedSource='+str(args.direct_creation).lower(),
            '-Dblue.probe.cold='+str(args.cold).lower(),'-Dblue.probe.observableRoot='+str(args.observable_root).lower(),'-Dblue.probe.rotateTargets='+str(args.rotate_targets).lower(),'-Dblue.probe.separateTimelines='+str(args.separate_timelines).lower(),*profiling,
            '-Dblue.probe.samples='+str(args.samples),'-Dblue.probe.orders='+','.join(map(str,counts)),'-cp',remote+'/lib/*:'+remote+'/qualification.jar',
            'myos.better.OwnerScaleProbe',outputs['Table'],account])]
    with worker(outputs, reports, args.instance, large_graph=large_graph) as identity:
        output_prefix = 'reports/qualification/' + account
        request={'DocumentName':'AWS-RunShellScript','InstanceIds':[identity],
            'OutputS3BucketName':outputs['Artifacts'],'OutputS3KeyPrefix':output_prefix,
            'Parameters':{'commands':commands,'executionTimeout':[str(execution_timeout)]},
            'Comment':'Isolated independent owner DynamoDB parity and bounded latency qualification'}
        request_file=reports/'owners-qualification-request.json';request_file.write_text(json.dumps(request))
        command=aws('ssm','send-command','--cli-input-json','file://'+str(request_file))['Command']['CommandId']
        result={'commandId':command,'instance':identity,'table':outputs['Table'],'account':account,'digest':digest,'counts':counts,'cold':args.cold,'profileSummaries':args.profile,'gasSchedule':'STANDARD','rotateTargets':args.rotate_targets,'separateTimelines':args.separate_timelines,'observableRoot':args.observable_root,'directCreation':args.direct_creation,'heapGiB':heap_gib,'cpu':cpu}
        selected_jars = [jars[0], *[p for p in (root/'build/install/myos-core-better/lib').glob('*.jar') if p.name.startswith(('blue-java-better-', 'myos-core-better-'))]]
        result['artifacts'] = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in selected_jars}
        (reports/'owners-qualification-command.json').write_text(json.dumps(result,indent=2)+'\n')
        print(json.dumps(result),flush=True)
        wait(command, identity, reports/'owners-qualification-current-result.json',
             output=(outputs['Artifacts'], output_prefix), timeout=execution_timeout+300)

if __name__=='__main__':main()
