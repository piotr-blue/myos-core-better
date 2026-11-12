#!/usr/bin/env python3
"""Summarize nested JFR host intervals without adding overlapping durations as elapsed time."""
import json,sys,re,statistics
from datetime import datetime
from pathlib import Path

def nanos(text):
    if isinstance(text,(int,float)):return float(text)
    m=re.fullmatch(r'PT(?:(\d+)H)?(?:(\d+)M)?([\d.]+)S',text)
    if not m:raise ValueError(text)
    return (float(m[1] or 0)*3600+float(m[2] or 0)*60+float(m[3]))*1e9

def seconds(text):return datetime.fromisoformat(re.sub(r'(\.\d{6})\d+', r'\1', text).replace('Z','+00:00')).timestamp()
def events(path):
    values=json.loads(path.read_text())['recording']['events'];rows=[]
    for raw in values:
        v=raw['values'];t=seconds(v['startTime']);duration=nanos(v['duration'])/1e9
        rows.append(dict(type=raw['type'],start=t,end=t+duration,millis=duration*1000,values=v,phase=v.get('phase',''),thread=v.get('eventThread',{}).get('javaThreadId')))
    return sorted(rows,key=lambda x:x['start'])
def contained(child,parent):return child['start']>=parent['start']-.00001 and child['end']<=parent['end']+.00001

def main():
    path=Path(sys.argv[1]);data=json.loads((path/('result.json' if (path/'result.json').exists() else 'client.json')).read_text());output={'selection':data['selection'],'calls':[],'startup':[]}
    byround={n:events(path/f'events-{n}.json') for n in {c['round'] for c in data['calls']}}
    for n,rows in byround.items():
        output['startup']+= [{'round':n,'millis':e['millis'],'subject':e['values'].get('subject'),'start':e['values']['startTime']} for e in rows if e['phase']=='startup.configuration']
    for c in data['calls']:
        rows=byround[c['round']];path_match='POST /api/documents/'+data['selection']['payment']+'/operations'
        posts=[e for e in rows if e['phase']=='http.request' and e['values']['subject']==path_match]
        post=posts[0 if c['kind']=='cold' else 1];start=post['start'];owners=[e for e in rows if e['type']=='myos.OwnerExecution' and e['values']['entry']==c['entry']]
        processes=[e for e in rows if e['phase']=='owner.process' and e['values']['entry']==c['entry']]
        assert len(owners)==3 and len(processes)==3
        last=max(e['end'] for e in owners)
        def compact(e):
            return {'phase':e['phase'] or e['type'],'subject':e['values'].get('subject',e['values'].get('owner','')),'startAfterServerRequestMillis':(e['start']-start)*1000,'durationMillis':e['millis'],'thread':e['thread']}
        phases=[e for e in rows if e['type']=='myos.HostPhase' and e['thread']==post['thread'] and contained(e,post) and not e['phase'].startswith(('store.','timeline.'))]
        sources=[e for e in rows if e['phase']=='source.acquire' and e['values']['account']==data['account'] and e['end']>=start and e['start']<=last]
        def io_summary(parent):
            io=[e for e in rows if e['phase'].startswith('store.') and e['thread']==parent['thread'] and contained(e,parent)]
            groups={}
            for e in io:
                g=groups.setdefault(e['phase'],{'calls':0,'inclusiveMillis':0});g['calls']+=1;g['inclusiveMillis']+=e['millis']
            return groups
        reqs=c['requests'];totals={'postRoundTripMillis':reqs[0]['elapsedMillis'],'observationHttpMillis':sum(r['elapsedMillis'] for r in reqs[1:]),'betweenRequestsMillis':c['parentObservedMillis']-sum(r['elapsedMillis'] for r in reqs)}
        observation=[]
        for e in rows:
            if e['phase']=='http.request' and e['values']['subject'].startswith('GET /api/documents/'+data['selection']['agreement']+'/') and e['start']>=start and e['start']<last+3:observation.append(compact(e))
        o={**{k:c[k] for k in ['round','kind','entry','parentObservedMillis','beforeVersion','afterVersion','expectedTotal']},'clientBreakdown':totals,
            'serverRequestMillis':post['millis'],'serverRequestToRootCommitMillis':(last-start)*1000,'ownerExecutionTotalMillis':sum(e['millis'] for e in owners),
            'ownerProcessTotalMillis':sum(e['millis'] for e in processes),'httpPhases':[compact(e) for e in phases],
            'ownerProcesses':[{**compact(e),'io':io_summary(e)} for e in processes],
            'owners':[{**compact(e),'detail':{k:round(nanos(e['values'][k])/1e6,4) for k in ['restoreNanos','admissionNanos','executionNanos','preparationNanos','commitNanos']}} for e in owners],
            'sources':[{**compact(e),'io':io_summary(e),'phases':[compact(x) for x in rows if x['thread']==e['thread'] and contained(x,e) and x['phase'].startswith(('timeline.','source.reader'))]} for e in sources],
            'requestIO':io_summary(post),'observationRequests':observation,'clientRequests':reqs}
        output['calls'].append(o)
    (path/'analysis.json').write_text(json.dumps(output,indent=2)+'\n')
    for c in output['calls']:
        print(c['round'],c['kind'],'client',round(c['parentObservedMillis'],2),'serverToCommit',round(c['serverRequestToRootCommitMillis'],2),'serverPost',round(c['serverRequestMillis'],2),'owners',round(c['ownerExecutionTotalMillis'],2))
        print('HTTP',[(e['phase'],round(e['startAfterServerRequestMillis'],2),round(e['durationMillis'],2)) for e in c['httpPhases']])
        print('Owners',[(round(e['startAfterServerRequestMillis'],2),round(e['durationMillis'],2)) for e in c['ownerProcesses']])
        print('Sources',[(e['subject'],round(e['startAfterServerRequestMillis'],2),round(e['durationMillis'],2)) for e in c['sources']])
    print('Startup',output['startup'])
if __name__=='__main__':main()
