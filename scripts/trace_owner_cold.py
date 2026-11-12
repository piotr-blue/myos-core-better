#!/usr/bin/env python3
"""Bounded cold/warm HTTPS trace on an existing synthetic graph in the verified preview.

Retains phase timings and identifiers only. Never exports cookies, API keys or QA credentials.
The operator command restarts the isolated service. The temporary JFR drop-in is removed in finally.
"""
import argparse,json,re,time,uuid,sys
from pathlib import Path
from aws import aws,verify
from qualify_preview import Client,prepared_users,wait_for_entry
from qualification_host import wait

class TraceClient(Client):
    def __init__(self,origin):
        super().__init__(origin);self.requests=[];self.epoch=0;self.recording=False
    def request(self,path,data=None,**options):
        start=time.perf_counter();wall=time.time_ns();result=super().request(path,data,**options);end=time.perf_counter()
        if self.recording:
            self.requests.append({'method':options.get('method') or ('POST' if data is not None else 'GET'),
                'path':path.split('?',1)[0],'status':result[0], 'startMillis':(start-self.epoch)*1000,
                'endMillis':(end-self.epoch)*1000,'elapsedMillis':(end-start)*1000,
                'startedAtUnixNanos':wall,'serverTiming':self.server_timing})
        return result

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('prior_report',type=Path)
    parser.add_argument('--rounds',type=int,default=2);args=parser.parse_args()
    if not 1<=args.rounds<=3:parser.error('Use one to three restart rounds')
    verify();root=Path(__file__).resolve().parents[1];reports=root/'reports'
    previous=json.loads(args.prior_report.read_text());assert previous['passed']
    core={r['OutputKey']:r['OutputValue'] for r in aws('cloudformation','describe-stacks','--stack-name','myos-better-canonical-20261108')['Stacks'][0]['Outputs']}
    console={r['OutputKey']:r['OutputValue'] for r in aws('cloudformation','describe-stacks','--stack-name','myos-better-canonical-console-20261108')['Stacks'][0]['Outputs']}
    assert previous['url']==console['Url']
    client=TraceClient(previous['url']);client.ok('/api/auth/login',prepared_users(reports,console)[0]);assert client.ok('/api/session')['account']==previous['account']
    selected=previous['results'][-1];prefix='cold-trace-'+uuid.uuid4().hex[:12];directory=reports/prefix;directory.mkdir()
    instance=core['Instance'];bucket=core['Artifacts'];results=[]
    def command(label,commands):
        output='reports/cold-trace/'+prefix+'/'+label
        result=aws('ssm','send-command','--document-name','AWS-RunShellScript','--instance-ids',instance,
            '--output-s3-bucket-name',bucket,'--output-s3-key-prefix',output,
            '--parameters',json.dumps({'commands':commands,'executionTimeout':['180']}))
        cid=result['Command']['CommandId'];return wait(cid,instance,directory/(label+'-command.json'),output=(bucket,output) if label.startswith('profile-') else None)
    dropin='/etc/systemd/system/myos-better.service.d/cold-request-trace.conf'
    marker='# owned-by: '+prefix
    try:
        command('enable',['set -euo pipefail', 'test ! -e '+dropin,
            'mkdir -p /etc/systemd/system/myos-better.service.d',
            "printf '%s\\n' '"+marker+"' '[Service]' 'Environment=JAVA_TOOL_OPTIONS=-XX:StartFlightRecording=name=cold-request,settings=profile,maxsize=64m,disk=true' > "+dropin,
            'systemctl daemon-reload'])
        for round_no in range(args.rounds):
            before=client.ok('/api/documents/'+selected['agreement']+'/head')['version']
            oldtotal=client.ok('/api/documents/'+selected['agreement']+'/value?path=/paidTotal')['value']
            restarted=command('restart-'+str(round_no),['set -euo pipefail','systemctl restart myos-better.service',
                'for i in $(seq 1 60); do if curl --fail --silent http://127.0.0.1:8080/health >/dev/null; then break; fi; sleep 1; done',
                'curl --fail --silent http://127.0.0.1:8080/health', 'date -u +%Y-%m-%dT%H:%M:%S.%NZ',
                'readlink -f /opt/myos-better/current'])
            for sample,label in enumerate(['cold','warm']):
                call={'round':round_no,'kind':label,'beforeVersion':before,'expectedTotal':oldtotal+42*(sample+1)}
                intent={'timeline':selected['timeline'],'channel':'input','operation':'record','request':{}}
                client.requests=[];client.epoch=time.perf_counter();client.recording=True
                submitted=client.ok('/api/documents/'+selected['payment']+'/operations',intent,key=prefix+'-'+str(round_no)+'-'+label)
                version=wait_for_entry(client,selected['agreement'],submitted['entry'],before)
                elapsed=(time.perf_counter()-client.epoch)*1000;client.recording=False
                history=client.ok(f"/api/documents/{selected['agreement']}/history/{version}")
                assert history['input']['mode']=='owner-local'
                for owner,path in [(selected['payment'],'/recordedAmount'),(selected['order'],'/paidTotal'),(selected['agreement'],'/paidTotal')]:
                    assert client.ok('/api/documents/'+owner+'/value?path='+path)['value']==call['expectedTotal']
                call.update(entry=submitted['entry'],afterVersion=version,parentObservedMillis=elapsed,requests=client.requests,outcome=history['outcome'])
                results.append(call);before=version
                (directory/'client.json').write_text(json.dumps({'account':previous['account'],'selection':selected,'calls':results},indent=2)+'\n')
                print(json.dumps({'round':round_no,'kind':label,'elapsedMillis':elapsed,'requests':len(client.requests)}),flush=True)
            profile=command('profile-'+str(round_no),['set -euo pipefail', 'pid=$(systemctl show myos-better.service --property MainPID --value)',
                'runuser -u myos-better -- jcmd "$pid" JFR.dump name=cold-request filename=/var/lib/myos-better/cold-request-trace.jfr >/dev/null',
                'runuser -u myos-better -- jcmd "$pid" JFR.stop name=cold-request >/dev/null',
                'jfr print --json --events myos.HostPhase,myos.OwnerExecution,myos.OwnerPreparation,jdk.GarbageCollection /var/lib/myos-better/cold-request-trace.jfr',
                'rm /var/lib/myos-better/cold-request-trace.jfr'])
            trace=json.loads(profile['StandardOutputContent']);(directory/('events-'+str(round_no)+'.json')).write_text(json.dumps(trace)+'\n')
        (directory/'result.json').write_text(json.dumps({'passed':True,'calls':results,'account':previous['account'],'selection':selected},indent=2)+'\n')
    finally:
        command('cleanup',['set -euo pipefail', "if test -f "+dropin+" && grep -Fxq '"+marker+"' "+dropin+"; then",
            'rm '+dropin,'systemctl daemon-reload',
            'pid=$(systemctl show myos-better.service --property MainPID --value)',
            'runuser -u myos-better -- jcmd "$pid" JFR.stop name=cold-request >/dev/null 2>&1 || true',
            'rm -f /var/lib/myos-better/cold-request-trace.jfr','fi',
            'curl --fail --silent http://127.0.0.1:8080/health'])
    print(json.dumps({'passed':True,'report':str(directory/'result.json')}),flush=True)
if __name__=='__main__':main()
