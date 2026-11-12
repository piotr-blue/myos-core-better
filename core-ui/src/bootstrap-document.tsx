import { captureContext, contextScope, type RequestContext } from "./account-context";
// Adapted from myos-java/apps/myos-blue/src/app/dev/documents/index.tsx:
// the bootstrap dialog, format controls and editable template use Core's API and UI primitives.
import { useState } from 'react';
import { Rocket } from 'lucide-react';
import { stringify } from 'yaml';
import { bootstrapTemplate, parseBootstrapPayload } from './bootstrap-payload';
import { stringifyBlue } from './blue-values';
import { Dialog } from './components/dialog';
import { api, mutate, type AdmissionProgress } from './api';

const draftKey = (context?: RequestContext) => `myos-bootstrap-draft:${contextScope(context)}`;
export function BootstrapDocument({navigate}:{navigate:(path:string)=>void}) {
 const [context] = useState(captureContext);
 const [open,setOpen]=useState(false),[source,setSource]=useState(''),[format,setFormat]=useState<'yaml'|'json'>('yaml');
 const [busy,setBusy]=useState(false),[error,setError]=useState(''),[accountId,setAccountId]=useState('');
 const [admission,setAdmission]=useState<AdmissionProgress>();
 function encode(value:unknown,next=format){return next==='yaml'?stringify(value):stringifyBlue(value,2)}
 function saveDraft(text: string, nextFormat = format, progress: AdmissionProgress | null = admission ?? null) {
  setSource(text);setFormat(nextFormat);
  if(accountId)sessionStorage.setItem(draftKey(context),JSON.stringify({source:text,format:nextFormat,admission:progress}));
 }
 async function show(){
  setOpen(true);setError('');
  try{
   const account=await api('account');setAccountId(account.accountId);
   const saved=sessionStorage.getItem(draftKey(context));
   if(saved){const draft=JSON.parse(saved);setSource(draft.source);setFormat(draft.format);setAdmission(draft.admission ?? undefined);if(draft.admission?.status==='FAILED')setError(draft.admission.problem?.detail || 'Bootstrap could not be processed.');}
   else {setSource(encode(bootstrapTemplate(account.accountId)));setAdmission(undefined);}
  }catch(e:any){setError(e.message)}
 }
 function changeFormat(next:'yaml'|'json'){
  try{saveDraft(encode(parseBootstrapPayload(source),next),next);setError('')}catch(e:any){setError(e.message)}
 }
 return <><button className="outline-button" onClick={show}><Rocket size={16}/> Bootstrap document</button>{open&&<Dialog title="Bootstrap document" onClose={()=>{if(!busy)setOpen(false)}}>
 <form onSubmit={async e=>{
  e.preventDefault();setBusy(true);setError('');
  try{
   const payload=parseBootstrapPayload(source);
   saveDraft(source);
   const result=await mutate('documents/bootstrap',payload,'POST',progress=>{
    setAdmission(progress);
    sessionStorage.setItem(draftKey(context),JSON.stringify({source,format,admission:progress}));
   });
   if(!result.sessionId)throw new Error('Bootstrap completed without a target session. Check its admission before retrying.');
   sessionStorage.removeItem(draftKey(context));
   navigate(`documents/${encodeURIComponent(result.sessionId)}`);setOpen(false);
  }catch(e:any){setError(e.message)}finally{setBusy(false)}
 }}>
 <p>Map each participant to an account ID or email, including your own account. Email uses that person's owned account, or creates a Personal account and invites them to MyOS. Each declared participant channel needs one mapping. Bootstrap creates new local timelines; existing timeline or actor assignments are rejected.</p>
 <div className="inline-actions"><span>Format</span>{(['yaml','json'] as const).map(option=><button type="button" key={option} className={format===option?'primary':'secondary'} disabled={busy} onClick={()=>changeFormat(option)}>{option.toUpperCase()}</button>)}<button type="button" className="link" disabled={busy} onClick={()=>{setAdmission(undefined);saveDraft(encode(bootstrapTemplate(accountId)),format,null)}}>Shared counter template</button></div>
 {admission&&<div role="status"><p>Bootstrap admission: <span className="mono">{admission.admissionId}</span> — {admission.status}</p></div>}
 {error&&<div role="alert" className="error">{error}</div>}
 <label className="field">Bootstrap payload<textarea className="code" rows={12} required value={source} disabled={busy} onChange={e=>{setAdmission(undefined);saveDraft(e.target.value,format,null)}}/></label>
 <p className="muted">Preparation runs in the background. Refreshing this page retains your payload and retry key. Submit the unchanged payload to resume waiting.</p>
 {accountId&&<p className="muted">Your account: <span className="mono">{accountId}</span></p>}
 <div className="dialog-footer"><button type="button" className="secondary" disabled={busy} onClick={()=>setOpen(false)}>Cancel</button><button className="primary" disabled={busy}>{busy?'Waiting for bootstrap…':admission?.status==='PENDING'?'Resume waiting':'Bootstrap'}</button></div>
 </form></Dialog>}</>
}
