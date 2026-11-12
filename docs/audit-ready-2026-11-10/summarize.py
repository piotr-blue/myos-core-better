#!/usr/bin/env python3
"""Summarize retained evidence without rerunning or hiding any sample."""
import json, math, statistics, xml.etree.ElementTree as ET
from pathlib import Path
ROOT=Path(__file__).resolve().parent

def distribution(values):
    values=sorted(values)
    return {'n':len(values),'minimum':min(values),'median':statistics.median(values),
            'p95NearestRank':values[math.ceil(.95*len(values))-1],'maximum':max(values)}

def tests():
    results={}
    for directory in sorted((ROOT/'evidence/tests').iterdir()):
        suites=[]; cases=[]
        for file in sorted(directory.glob('*.xml')):
            suite=ET.parse(file).getroot()
            suites.append({k:suite.get(k) for k in ('name','tests','failures','errors','skipped','time')})
            for case in suite.findall('testcase'):
                failures=[{'kind':x.tag,'message':x.get('message'), 'detail':x.text} for x in case if x.tag in ('failure','error','skipped')]
                cases.append({'class':case.get('classname'),'name':case.get('name'),'seconds':float(case.get('time','0')),
                              'status':failures[0]['kind'] if failures else 'passed','details':failures})
        results[directory.name]={'totals':{k:sum(int(s[k] or 0) for s in suites) for k in ('tests','failures','errors','skipped')},'suites':suites,'cases':cases}
    (ROOT/'TEST-RESULTS.json').write_text(json.dumps(results,indent=2)+'\n')
    print({k:v['totals'] for k,v in results.items()})

def aws():
    result={}
    for directory in sorted((ROOT/'evidence').glob('aws-*')):
        file=directory/'owners-qualification-current-result.json'
        if not file.exists(): continue
        record=json.loads(file.read_text()); assert record['Status']=='Success',file
        rows=[json.loads(line) for line in record['StandardOutputContent'].splitlines() if line.startswith('{')]
        final=next(row for row in reversed(rows) if 'results' in row)
        comparisons=[r for r in rows if r.get('phase')=='comparison']
        cases=[]
        for case in final['results']:
            calls=case['calls']; assert all(c['parity'] is True and c['appliedOwners']==3 for c in calls)
            cases.append({'orders':case['orders'],'documents':case['documents'],'timelineCount':case.get('timelineCount',1),
                          'initializationMillis':case['initializationMillis'],'quietSourceSetupMillis':case.get('quietSourceSetupMillis',0),
                          'processingMillis':distribution([c['durableProcessingMillis'] for c in calls]),
                          'acquisitionMillis':distribution([c['acquisitionMillis'] for c in calls]),
                          'combinedMillis':distribution([c['acquisitionMillis']+c['durableProcessingMillis'] for c in calls]),
                          'samples':calls, 'io':[r for r in rows if r.get('phase')=='io' and r['orders']==case['orders']]})
        for row in comparisons:
            for field in ('blueId','gas','publicEvents'): assert row['actual'][field]==row['expected'][field],(file,field,row)
        assert len(comparisons)==sum(len(c['samples']) for c in cases),file
        result[directory.name]={'cases':cases,'comparisons':comparisons,'boundary':{k:v for k,v in final.items() if k!='results'}}
    (ROOT/'AWS-RESULTS.json').write_text(json.dumps(result,indent=2)+'\n')
    print({name:[(c['orders'],c['processingMillis']['median'],c['acquisitionMillis']['median'],c['combinedMillis']['median']) for c in record['cases']] for name,record in result.items()})

if __name__=='__main__':
    tests(); aws()
