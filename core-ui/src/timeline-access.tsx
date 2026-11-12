import { TimelinesApi, type AccountIdentity } from "@blue-labs/myos-sdk";
import { accountCommand, coreConfiguration } from "./account-api";
import { accountContext } from "./account-context";
import {useEffect,useState} from 'react';
import {api,mutate} from './api';
import {PageHeader,CopyValue,CodeViewer} from './components/viewer';
import {DataTable} from './components/data-table';
export function ImportTimeline({navigate, account}:{navigate:(route:string)=>void; account: AccountIdentity}){
 const [endpoint,setEndpoint]=useState(''),[timelineId,setTimelineId]=useState(''),[readerKeyId,setReader]=useState(''),[name,setName]=useState(''),[accessId,setAccessId]=useState(''),[error,setError]=useState(''),[busy,setBusy]=useState(false);
 const context = accountContext(account);
 return <><PageHeader title="Import timeline" back={()=>navigate('timelines')}/>
 <form className="panel panel-pad" onSubmit={async e=>{e.preventDefault();setBusy(true);setError('');const input={endpoint,timelineId,readerKeyId,name:name||timelineId};try{
   const result=await accountCommand(context,'import-timeline',input,(_api,key)=>new TimelinesApi(coreConfiguration(context)).importTimeline({idempotencyKey:key,importTimeline:input}));navigate(`timelines/${encodeURIComponent(result.timelineId)}`);
 }catch(e:any){setError(e.message)}finally{setBusy(false)}}}>
 <p>Import with an existing account-managed reader ID. Core verifies ownership; this screen does not create or export signing keys. A <a href="#timelines/import?access=1" onClick={()=>document.getElementById('public-access-import')?.scrollIntoView()}>public Timeline Access document</a> can be imported separately below.</p>
 {error&&<div role="alert" className="error">{error}</div>}
 <label className="field">Name<input value={name} onChange={e=>setName(e.target.value)} placeholder="Partner orders"/></label>
 <label className="field">Provider endpoint<input required type="url" value={endpoint} onChange={e=>setEndpoint(e.target.value)} placeholder="https://provider.example/timeline-provider"/></label>
 <label className="field">Timeline BlueId<input required value={timelineId} onChange={e=>setTimelineId(e.target.value)}/></label>
 <label className="field">Existing reader ID<input required value={readerKeyId} onChange={e=>setReader(e.target.value)}/></label>
 <p className="muted">MyOS verifies the provider signature and retains signed responses for processing proofs. Importing grants no append authority.</p>
 <button className="primary" disabled={busy||!readerKeyId}>{busy?'Verifying provider…':'Import timeline'}</button>
 </form>
 <form id="public-access-import" className="panel panel-pad" onSubmit={async e=>{e.preventDefault();setBusy(true);setError('');const input={endpoint,accessId};try{
   const result=await accountCommand(context,'import-timeline-access',input,(_api,key)=>new TimelinesApi(coreConfiguration(context)).importTimelineAccess({idempotencyKey:key,importTimelineAccess:input}));navigate(`documents/${encodeURIComponent(result.sessionId)}`);
 }catch(e:any){setError(e.message)}finally{setBusy(false)}}}>
 <h2>Import public Timeline Access document</h2><p>This imports the provider's public access publication as a native document. It does not create a reader credential or grant timeline access.</p>
 <label className="field">Access provider endpoint<input required type="url" value={endpoint} onChange={e=>setEndpoint(e.target.value)}/></label>
 <label className="field">Timeline Access document BlueId<input required value={accessId} onChange={e=>setAccessId(e.target.value)}/></label>
 <button className="primary" disabled={busy||!accessId}>Import public access document</button>
 </form></>
}
export function TimelineAccess({id}:{id:string}){
 const [rows,setRows]=useState<any[]>([]),[key,setKey]=useState(''),[busy,setBusy]=useState(false),[error,setError]=useState('');
 const load=()=>api(`timelines/${encodeURIComponent(id)}/access`).then(r=>setRows(r.items)).catch(e=>setError(e.message));useEffect(()=>{load()},[id]);
 async function change(accessId:string|undefined,allowedKeys:string[]){setBusy(true);setError('');try{await mutate(`timelines/${encodeURIComponent(id)}/access`,{accessId,allowedKeys});setKey('');await load()}catch(e:any){setError(e.message)}finally{setBusy(false)}}
 return <section className="panel panel-pad"><h3>Access</h3><p>Reader keys can fetch this timeline's entries. They cannot append entries or read other timelines.</p>{error&&<div role="alert" className="error">{error}</div>}
 <form onSubmit={e=>{e.preventDefault();const values=[...new Set(key.trim().split(/[\s,]+/))];if(values.some(value=>!/^([a-f0-9]{64})$/.test(value))){setError('Each public key must contain 64 lowercase hexadecimal characters.');return}change(undefined,values)}}><label className="field">Public reader keys<textarea required rows={3} value={key} onChange={e=>setKey(e.target.value)} placeholder="One key per line; 64 lowercase hexadecimal characters each"/></label><p className="muted">Keys entered together are published in one Timeline Access document. Include every participant's key when preparing an access-aware Agreement.</p><button className="primary" disabled={busy}>Grant access</button></form>
 <DataTable data={rows.flatMap(a=>a.allowedKeys.map((k:string)=>({access:a,key:k})))} emptyMessage="No reader keys have access." columns={[
 {key:'key',label:'Allowed key',render:(r:any)=><span className="mono" style={{overflowWrap:'anywhere'}}>{r.key} <CopyValue value={r.key}/></span>},
 {key:'access',label:'Access document',render:(r:any)=><a href={`#references/${encodeURIComponent(r.access.id)}`}>{r.access.id}</a>},
 {key:'remove',label:'',render:(r:any)=><button className="link" disabled={busy} onClick={()=>change(r.access.id,r.access.allowedKeys.filter((k:string)=>k!==r.key))}>Revoke</button>}
 ]}/><details><summary>Published access documents</summary>{rows.map(r=><CodeViewer key={r.id} value={r.current}/>)}</details></section>
}
