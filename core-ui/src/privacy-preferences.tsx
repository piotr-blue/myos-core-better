import { useEffect, useRef, useState } from 'react';
import { preferenceEvent, preferenceKey, readAnalyticsPreference, saveAnalyticsPreference } from '../observability/preferences.mjs';
import './privacy-preferences.css';

export function PrivacyPreferences({ autoPrompt = true }: { autoPrompt?: boolean }) {
  const [choice, setChoice] = useState(readAnalyticsPreference);
  const [editing, setEditing] = useState(false);
  const returnFocus = useRef<HTMLElement | null>(null);
  const heading = useRef<HTMLHeadingElement>(null);
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const open = (event: Event) => { returnFocus.current = (event as CustomEvent<HTMLElement>).detail; setEditing(true); };
    window.addEventListener('myos-privacy-open', open);
    const changed = (event: Event) => setChoice((event as CustomEvent<boolean>).detail === true);
    const stored = (event: StorageEvent) => {
      if (event.key === preferenceKey || event.key === null) setChoice(readAnalyticsPreference());
    };
    window.addEventListener(preferenceEvent, changed);
    window.addEventListener('storage', stored);
    return () => {
      window.removeEventListener('myos-privacy-open', open);
      window.removeEventListener(preferenceEvent, changed);
      window.removeEventListener('storage', stored);
    };
  }, []);
  useEffect(() => {
    if (editing && dialog.current) {
      if (!dialog.current.open) dialog.current.showModal();
      heading.current?.focus();
      const element = dialog.current;
      return () => { if (element.open) element.close(); };
    }
  }, [editing]);
  const dismiss = () => {
    dialog.current?.close();
    setEditing(false);
    requestAnimationFrame(() => returnFocus.current?.focus());
  };
  const choose = (analytics: boolean) => {
    saveAnalyticsPreference(analytics);
    setChoice(analytics);
    dismiss();
  };
  if (!editing && (!autoPrompt || choice !== null)) return null;
  const content = <>
    <div className="myos-privacy-heading"><span aria-hidden="true">M</span><div><p>YOUR PRIVACY</p><h2 id="myos-privacy-title" tabIndex={-1} ref={heading}>Public page analytics</h2></div></div>
    <p>Optional analytics helps us understand visits to public pages. Essential cookies keep you signed in.</p>
    <p className="myos-privacy-detail">Signed-in service diagnostics remain on regardless of this choice. During beta, session recordings may include visible content and ordinary form values. Known credential fields are excluded.</p>
    {editing && <p className="myos-privacy-current" role="status">{choice === null ? 'No choice selected.' : `Current choice: Public analytics ${choice ? 'allowed' : 'rejected'}.`}</p>}
    <div className="myos-privacy-actions"><button type="button" aria-pressed={choice === false} onClick={() => choose(false)}>{choice === false && <span aria-hidden="true">✓ </span>}Reject public analytics</button><button type="button" aria-pressed={choice === true} onClick={() => choose(true)}>{choice === true && <span aria-hidden="true">✓ </span>}Allow public analytics</button></div>
    <p className="myos-privacy-footnote">Change your public analytics choice anytime in Cookie settings.</p>
  </>;
  return editing ? <dialog className="myos-privacy myos-privacy-dialog" aria-labelledby="myos-privacy-title" ref={dialog} onCancel={event => { event.preventDefault(); dismiss(); }}>
    {content}<button type="button" className="myos-privacy-link myos-privacy-close" onClick={dismiss}>Close settings</button>
  </dialog> : <section className="myos-privacy myos-privacy-banner" aria-labelledby="myos-privacy-title">{content}</section>;
}

export function PrivacySettingsButton() {
  return <button type="button" className="myos-privacy-link" aria-haspopup="dialog" onClick={event => {
    const target = event.currentTarget.closest('details')?.querySelector('summary') ?? event.currentTarget;
    window.dispatchEvent(new CustomEvent('myos-privacy-open', { detail: target }));
  }}>Cookie settings</button>;
}
