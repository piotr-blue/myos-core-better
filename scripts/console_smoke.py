#!/usr/bin/env python3
"""Bounded live HTTPS qualification. Test users receive no email; credentials remain local/private."""
import argparse,http.cookiejar,json,os,secrets,tempfile,time,urllib.request,urllib.error,uuid
from pathlib import Path
from aws import aws,verify
from console_infra import STACK

root=Path(__file__).resolve().parents[1]
reports=root/'reports';reports.mkdir(exist_ok=True)
credentials=reports/'console-qa-credentials.json'
parser=argparse.ArgumentParser();parser.add_argument('--prepare',action='store_true');args=parser.parse_args()
verify()
stack=aws('cloudformation','describe-stacks','--stack-name',STACK)['Stacks'][0]
if stack['StackStatus']!='CREATE_COMPLETE':raise RuntimeError('Console stack is not ready')
outputs={x['OutputKey']:x['OutputValue'] for x in stack['Outputs']}

def sensitive(action,payload):
    descriptor,name=tempfile.mkstemp(prefix='console-cognito-',suffix='.json',dir=reports)
    try:
        with os.fdopen(descriptor,'w') as stream:json.dump(payload,stream)
        return aws('cognito-idp',action,'--cli-input-json','file://'+name)
    finally:Path(name).unlink(missing_ok=True)

if credentials.exists():
    saved=json.loads(credentials.read_text())
    if saved['url']!=outputs['Url'] or saved['pool']!=outputs['UserPool']:raise RuntimeError('QA credentials belong to another deployment')
else:
    saved={'url':outputs['Url'],'pool':outputs['UserPool'],'users':[]}
    for label in ('alice','bob'):
        email='better-'+label+'-'+uuid.uuid4().hex[:12]+'@example.invalid'
        password=secrets.token_urlsafe(24)+'aA1'
        sensitive('admin-create-user',{'UserPoolId':outputs['UserPool'],'Username':email,'MessageAction':'SUPPRESS','UserAttributes':[{'Name':'email','Value':email},{'Name':'email_verified','Value':'true'}]})
        sensitive('admin-set-user-password',{'UserPoolId':outputs['UserPool'],'Username':email,'Password':password,'Permanent':True})
        saved['users'].append({'email':email,'password':password})
    descriptor=os.open(credentials,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    with os.fdopen(descriptor,'w') as stream:json.dump(saved,stream,indent=2)
if args.prepare:
    print(json.dumps({'usersPrepared':len(saved['users']),'url':saved['url'],'credentials':'local protected reports file; not exported'}))
    raise SystemExit(0)

class Client:
    def __init__(self):self.cookies=http.cookiejar.CookieJar();self.http=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookies))
    def request(self,path,data=None,key=None,origin=None):
        headers={}
        if data is not None:headers.update({'Content-Type':'application/json','Origin':origin or saved['url']})
        if key is not None:headers['Idempotency-Key']=key
        request=urllib.request.Request(saved['url']+path,data=None if data is None else json.dumps(data).encode(),headers=headers)
        try:
            with self.http.open(request,timeout=60) as response:return response.status,json.load(response)
        except urllib.error.HTTPError as failure:
            try:return failure.code,json.load(failure)
            except ValueError:return failure.code,{'error':'non-json response'}
    def ok(self,path,data=None,key=None):
        status,body=self.request(path,data,key)
        if status!=200:raise RuntimeError('HTTP '+str(status)+' for '+path+': '+str(body.get('code','unknown')))
        return body

report={'url':saved['url'],'checks':[],'startedAt':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime())}
clients=[]
for user in saved['users']:
    client=Client();client.ok('/api/auth/login',{'email':user['email'],'password':user['password']});clients.append(client)
alice,bob=clients
assert Client().request('/api/documents')[0]==401
assert alice.request('/api/documents',{'source':'count: 0'},'cross-origin',origin='https://attacker.invalid')[0]==403
report['checks'].append('real Cognito login, anonymous rejection and cross-origin write rejection')
key='smoke-'+uuid.uuid4().hex
source=(root/'examples/counter.blue').read_text()
created=alice.ok('/api/documents',{'source':source},key)
assert created==alice.ok('/api/documents',{'source':source},key)
document=created['document']
state=alice.ok('/api/documents/'+document)
assert state['data']['count']==0 and state['data']['initialized'] is True
assert bob.request('/api/documents/'+document)[0]==404
assert all(item['document']!=document for item in bob.ok('/api/documents')['items'])
call={'channel':'input','operation':'increment','request':{},'timestamp':1}
result=alice.ok('/api/documents/'+document+'/operations',call,key+'-increment')
assert result==alice.ok('/api/documents/'+document+'/operations',call,key+'-increment')
assert alice.ok('/api/documents/'+document)['data']['count']==1
assert alice.ok('/api/documents/'+document+'?version=1')['data']['count']==0
report['checks'].append('counter initialization, operation, exact retry, immutable history and cross-account isolation')
started=time.perf_counter();demo=alice.ok('/api/examples/order-payment',{},key+'-demo')
for attempt in range(100):
    root_state=alice.ok('/api/documents/'+demo['document'])
    if root_state['data']['paidTotal']==42:break
    time.sleep(.1)
else:raise RuntimeError('Parent did not receive payment')
assert root_state['data']['initializedOrders']==1
assert len(alice.ok('/api/documents/'+demo['document']+'/children')['items'])==1
assert len(alice.ok('/api/documents/'+demo['order']+'/children')['items'])==2
assert 'Agreement Payment Received' in json.dumps(alice.ok('/api/documents/'+demo['document']+'/events'))
assert demo==alice.ok('/api/examples/order-payment',{},key+'-demo')
assert alice.ok('/api/documents/'+demo['document'])['data']['paidTotal']==42
report['checks'].append('four-document bootstrap, order/payment operations, parent reaction, public event and scenario retry')
report.update(passed=True,counter=document,agreement=demo['document'],order=demo['order'],payment=demo['payment'],paidTotal=42,scenarioMillis=(time.perf_counter()-started)*1000)
(reports/'console-http-result.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report))
