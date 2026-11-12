#!/usr/bin/env python3
"""Exercise the private HTTP service: agreement -> order -> payment -> order -> agreement."""
import argparse,json,time,uuid,urllib.request
from pathlib import Path

parser=argparse.ArgumentParser()
parser.add_argument('--url',default='http://127.0.0.1:8080')
parser.add_argument('--account',default='demo-'+str(uuid.uuid4()))
parser.add_argument('--output',type=Path)
args=parser.parse_args()
root=Path(__file__).resolve().parents[1]

def post(path,body):
    request=urllib.request.Request(args.url.rstrip('/')+path,data=json.dumps(body).encode(),headers={'Content-Type':'application/json'},method='POST')
    with urllib.request.urlopen(request,timeout=20) as response:return json.load(response)

def request(document,**values):return {'account':args.account,'document':document,**values}

def read():return post('/documents/read',request('agreement',expanded=False))

source=(root/'examples/agreement.blue').read_text()
created=post('/documents/create',request('agreement',command='create',source=source))
added=post('/documents/call',request('agreement',command='add-order',channel='input',operation='addOrder',request={'key':'o1','order':json.loads((root/'examples/order.json').read_text())},timestamp=1))
child=post('/documents/embedded',request('agreement',path='/orders/o1/payments/p0'))['document']
paid=post('/documents/call',request(child,command='record-payment',channel='input',operation='record',request={'invoice':'hello'},timestamp=2))
for attempt in range(300):
    state=read()
    if state['data']['paidTotal']==42:break
    time.sleep(.1)
else:raise RuntimeError('Parent propagation did not complete in 30 seconds')
expanded=post('/documents/read',request('agreement',expanded=True))
events=post('/documents/events',request('agreement',after=0,limit=100))
report={'account':args.account,'source':source,'created':created,'added':added,'payment':paid,'agreement':expanded,'events':events}
output=args.output or root/'reports'/('demo-'+args.account+'.json')
output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps({'account':args.account,'paidTotal':state['data']['paidTotal'],'report':str(output)},indent=2))
