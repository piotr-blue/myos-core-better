import { useEffect, useRef, useState } from 'react';
import { ImagePicker } from './image-picker';
export { ImagePicker } from './image-picker';

export type AccountInput = {
  kind: 'BUSINESS' | 'PERSONAL';
  profile: { displayName: string; website?: string | null; address?: string };
};
export type CreatedAccount = { accountId: string; membershipGeneration: number; provisioning?: 'PENDING' | 'READY' };
const ready = (result?: CreatedAccount) => !!result && result.provisioning !== 'PENDING';
function transient(failure: unknown) {
  if (failure instanceof DOMException && failure.name === 'AbortError') return false;
  const status = failure && typeof failure === 'object' && 'status' in failure ? Number(failure.status) : 0;
  return !status || status === 408 || status === 429 || status >= 500;
}
type Intent = {
  input: AccountInput;
  requestId: string;
  result?: CreatedAccount;
  imageHash?: string;
  imageRequestId?: string;
};
export function imageFileError(file: File) {
  if (!file.size || file.size > 5 * 1024 * 1024) return 'Choose a nonempty image no larger than 5 MiB.';
  if (!['', 'application/octet-stream', 'image/jpeg', 'image/png'].includes(file.type)) return 'Choose a JPEG or PNG image.';
  return '';
}
export async function imageFingerprint(file: File) {
  const error = imageFileError(file);
  if (error) throw new Error(error);
  return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', await file.arrayBuffer())), b => b.toString(16).padStart(2, '0')).join('');
}
function readIntent(key: string): Intent | undefined {
  const raw = sessionStorage.getItem(key);
  if (!raw) return;
  const value = JSON.parse(raw) as Intent;
  if (!value.requestId || !value.input?.profile?.displayName || !['BUSINESS', 'PERSONAL'].includes(value.input.kind))
    throw new Error('The saved account creation could not be read. Reopen account selection.');
  return value;
}

export function AccountCreationForm({ scope, additional = false, create, upload, complete }: {
  scope: string;
  additional?: boolean;
  create: (input: AccountInput, requestId: string) => Promise<CreatedAccount>;
  upload: (account: CreatedAccount, file: File, requestId: string) => Promise<unknown>;
  complete: (account: CreatedAccount) => void;
}) {
  const key = 'myos-account-creation-v1:' + scope;
  const [intent, setIntent] = useState(() => readIntent(key));
  const [kind, setKind] = useState<AccountInput['kind']>(intent?.input.kind ?? 'BUSINESS');
  const [name, setName] = useState(intent?.input.profile.displayName ?? '');
  const [website, setWebsite] = useState(intent?.input.profile.website ?? '');
  const [address, setAddress] = useState(intent?.input.profile.address ?? '');
  const [file, setFile] = useState<File | null>(null);
  const [fileProblem, setFileProblem] = useState('');
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState('');
  const waiting = useRef<(() => void) | undefined>(undefined);
  const submitting = useRef(false), alive = useRef(true);
  useEffect(() => {
    alive.current = true;
    // Delay only the mount recovery so React's effect cleanup can cancel it.
    const recovery = setTimeout(() => { if (intent && !ready(intent.result)) void submit(true); }, 0);
    return () => { alive.current = false; clearTimeout(recovery); waiting.current?.(); };
  }, []);
  const retain = (next: Intent) => { sessionStorage.setItem(key, JSON.stringify(next)); if (alive.current) setIntent(next); };
  const finish = (result: CreatedAccount) => { if (!alive.current || !ready(result)) return; complete(result); sessionStorage.removeItem(key); };
  function pause(milliseconds: number) {
    return new Promise<void>(resolve => {
      const timer = setTimeout(() => { waiting.current = undefined; resolve(); }, milliseconds);
      waiting.current = () => { clearTimeout(timer); waiting.current = undefined; resolve(); };
    });
  }
  async function submit(resuming = false) {
    if (submitting.current || fileProblem) return;
    submitting.current = true; setBusy(true); setError(''); setNotice('');
    let next = intent;
    try {
      const imageHash = file ? await imageFingerprint(file) : undefined;
      if (!alive.current) return;
      if (next?.imageHash && file && imageHash !== next.imageHash) throw new Error('Reselect the original image to retry, or continue to the account and edit its logo in Settings.');
      if (!next) {
        next = { input: { kind, profile: { displayName: name.trim(), website: website.trim() || null,
          ...(kind === 'BUSINESS' && address.trim() ? { address: address.trim() } : {}) } }, requestId: crypto.randomUUID(),
          ...(imageHash ? { imageHash, imageRequestId: crypto.randomUUID() } : {}) };
        retain(next);
      }
      let checks = 0;
      while (alive.current && !ready(next.result)) {
        try {
          const result = await create(next.input, next.requestId);
          if (!alive.current) return;
          next = { ...next, result }; retain(next); setNotice('');
        } catch (failure) {
          if ((!next.result && !resuming) || !transient(failure)) throw failure;
          if (!alive.current) return;
          setNotice(next.result ? 'Your account is saved. Checking availability again…' : 'Checking whether your account was saved…');
        }
        if (!ready(next.result)) await pause(Math.min(250 * 2 ** Math.min(checks++, 5), 5000));
      }
      if (!alive.current) return;
      if (next.imageHash) {
        if (!file || !next.imageRequestId) throw new Error('Reselect the original image to finish the upload.');
        await upload(next.result!, file, next.imageRequestId);
      }
      finish(next.result!);
    } catch (failure) {
      // A definite validation rejection occurs before account creation. Let the
      // user correct the fields; uncertain outcomes retain the original intent.
      if (!next?.result && failure && typeof failure === 'object' && 'status' in failure && [400, 422].includes(Number(failure.status))) {
        sessionStorage.removeItem(key); if (alive.current) setIntent(undefined);
      }
      if (alive.current) setError((ready(next?.result) ? 'Account created. The image was not confirmed. ' : next?.result ? 'Account saved. ' : '') + (failure instanceof Error ? failure.message : 'Unable to complete account setup.'));
    } finally { submitting.current = false; if (alive.current) setBusy(false); }
  }
  return <section className="settings-card panel panel-pad account-management account-creation">
    <h2>{additional ? 'Create another account' : 'Choose your account type'}</h2>
    <p>{additional ? 'Add a workspace to your existing login.' : 'Use MyOS for your business or for yourself.'} Personal accounts can be converted to Business in Settings.</p>
    <form onSubmit={e => { e.preventDefault(); void submit(); }}>
      <fieldset className="account-kind-toggle" disabled={busy || !!intent}>
        <legend>Account type</legend>
        {(['BUSINESS', 'PERSONAL'] as const).map(value => <label key={value}><input type="radio" name="account-type" checked={kind === value} onChange={() => { setKind(value); setFile(null); setFileProblem(''); setError(''); }} /><span>{value === 'BUSINESS' ? 'Business' : 'Personal'}</span></label>)}
      </fieldset>
      <label className="account-field field">{kind === 'BUSINESS' ? 'Business Name' : 'Display name'}<input required maxLength={160} value={name} disabled={busy || !!intent} autoComplete={kind === 'BUSINESS' ? 'organization' : 'name'} onChange={e => setName(e.target.value)} /></label>
      <label className="account-field field">Website (optional)<input type="url" maxLength={2048} placeholder="https://example.com" autoComplete="url" value={website} disabled={busy || !!intent} onChange={e => setWebsite(e.target.value)} /></label>
      {kind === 'BUSINESS' && <label className="account-field field">Address (optional)<textarea maxLength={1000} rows={3} autoComplete="street-address" value={address} disabled={busy || !!intent} onChange={e => setAddress(e.target.value)} /></label>}
      {kind === 'BUSINESS' && <div className="account-field field">
        <span>Business logo (optional)</span>
        <ImagePicker compact alt="Selected account logo" file={file} disabled={busy || (!!intent && !intent.imageHash)} label={file ? 'Change logo' : 'Upload logo'} choose={value => {
        const problem = value ? imageFileError(value) : ''; setError(problem); setFileProblem(problem); setFile(problem ? null : value);
      }} />
      </div>}
      {intent && !intent.result && <p role="status">Account creation is awaiting confirmation. The saved request will be reused.</p>}
      {intent?.result && !ready(intent.result) && <p role="status">Account saved. Preparing your workspace…</p>}
      {ready(intent?.result) && <p>The account is ready. Retry only finishes its image upload.</p>}
      {notice && <p role="status">{notice}</p>}
      {error && <p role="alert" className="error">{error}</p>}
      <button className="button primary" disabled={busy || !!fileProblem || !name.trim() || (ready(intent?.result) && !!intent?.imageHash && !file)}>{busy ? (intent?.result && !ready(intent.result) ? 'Preparing…' : 'Saving…') : intent?.result && !ready(intent.result) ? 'Check account status' : intent?.result ? 'Retry image upload' : intent ? 'Retry account creation' : 'Create account'}</button>
      {ready(intent?.result) && <button type="button" className="button secondary" disabled={busy} onClick={() => finish(intent!.result!)}>Continue to account</button>}
    </form>
  </section>;
}

export function AccountImageEditor({ imageUrl, business, disabled, upload, remove }: {
  imageUrl?: string | null; business: boolean; disabled: boolean;
  upload: (file: File) => Promise<unknown>; remove: () => Promise<unknown>;
}) {
  const [file, setFile] = useState<File | null>(null), [error, setError] = useState(''), [busy, setBusy] = useState(false);
  const running = useRef(false);
  const perform = async (action: () => Promise<unknown>) => {
    if (running.current || disabled) return;
    running.current = true; setBusy(true); setError('');
    try { await action(); setFile(null); } catch (e) { setError(e instanceof Error ? e.message : 'Unable to save the account image.'); }
    finally { running.current = false; setBusy(false); }
  };
  if (!business) return null;
  return <section className="settings-card panel panel-pad account-management image-settings-card" aria-label="Account logo">
    <h2>Logo</h2>
    <p className="image-settings-description">Help people recognize your business with a logo.</p>
    <ImagePicker imageUrl={imageUrl} alt="Account logo" file={file} disabled={disabled || busy} label="Choose account image" choose={value => { const problem = value ? imageFileError(value) : ''; setError(problem); setFile(problem ? null : value); }}>
      <button type="button" className="button primary" disabled={disabled || busy || !file} onClick={() => file && void perform(() => upload(file))}>{busy ? 'Saving…' : 'Save account image'}</button>
      {imageUrl && <button type="button" className="button secondary" disabled={disabled || busy} onClick={() => void perform(remove)}>Remove account image</button>}
    </ImagePicker>
    {error && <p role="alert">{error}</p>}
  </section>;
}
