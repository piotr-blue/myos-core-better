import { useState, type FormEvent } from 'react';
import { auth } from './better-auth-api';
type Mode = 'login' | 'signup' | 'confirm' | 'forgot' | 'reset';
export function SignIn({onSignedIn}: {onSignedIn: () => void}) {
  const [mode, setMode] = useState<Mode>('login');
  const [email, setEmail] = useState(''), [password, setPassword] = useState(''), [code, setCode] = useState('');
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState('');
  const title = {login:'Sign in', signup:'Create your account', confirm:'Confirm your email', forgot:'Reset your password', reset:'Choose a new password'}[mode];
  async function submit(event: FormEvent) {
    event.preventDefault(); setBusy(true); setError(''); setNotice('');
    try {
      await auth(mode, {email, password, code});
      if (mode === 'login') onSignedIn();
      else if (mode === 'signup') { setMode('confirm'); setNotice('Enter the confirmation code sent to your email.'); }
      else if (mode === 'forgot') { setMode('reset'); setNotice('Enter the password reset code from your email.'); }
      else { setMode('login'); setNotice('You can now sign in.'); }
    } catch (failure: any) { if(failure.code==='UserNotConfirmedException')setMode('confirm'); setError(failure.message); }
    finally { setBusy(false); }
  }
  return <div className="auth-page"><div className="auth-brand"><img src="/assets/myos-logo.svg" width="150" alt="MyOS"/><span className="subtle">Better</span><div className="auth-pitch"><h1>Your documents.<br/>Running together.</h1><p>Bootstrap an agreement, call its operations, and follow events through orders and payments.</p></div></div><div className="auth-form"><h2>{title}</h2><p className="auth-subtitle">MyOS Better has its own accounts and documents.</p><form onSubmit={submit}>
    <label className="field">Email<input type="email" autoComplete="username" value={email} onChange={e=>setEmail(e.target.value)} required/></label>
    {['login','signup','reset'].includes(mode) && <label className="field">Password<input type="password" autoComplete={mode==='login'?'current-password':'new-password'} minLength={mode==='login'?1:12} value={password} onChange={e=>setPassword(e.target.value)} required/>{mode!=='login'&&<small>At least 12 characters, with uppercase, lowercase and a number.</small>}</label>}
    {['confirm','reset'].includes(mode)&&<label className="field">Confirmation code<input autoComplete="one-time-code" value={code} onChange={e=>setCode(e.target.value)} required/></label>}
    {error&&<div role="alert" className="error">{error}</div>}{notice&&<p role="status">{notice}</p>}
    <button className="primary" disabled={busy}>{busy?'Please wait…':title}</button>
  </form><div className="auth-links"><button className="text-button" onClick={()=>{setMode(mode==='login'?'signup':'login');setError('')}}>{mode==='login'?'Create an account':'Back to sign in'}</button><button className="text-button" onClick={()=>setMode('forgot')}>Forgot password?</button>{mode==='confirm'&&<button className="text-button" disabled={busy} onClick={async()=>{setBusy(true);try{await auth('resend',{email});setNotice('A new confirmation code has been requested.')}catch(e:any){setError(e.message)}finally{setBusy(false)}}}>Resend code</button>}</div></div></div>;
}
