import { useEffect, useId, useRef, useState, type ReactNode } from 'react';
import './image-picker.css';

/** Shared presentation only; each portal owns validation and persistence. */
export function ImagePicker({ file, imageUrl, disabled, choose, label, alt, avatar = false, compact = false, children }: {
  file?: File | null | undefined;
  imageUrl?: string | null | undefined;
  disabled: boolean;
  choose: (file: File | null) => void;
  label: string;
  alt: string;
  avatar?: boolean;
  compact?: boolean;
  children?: ReactNode;
}) {
  const [preview, setPreview] = useState('');
  const [failedSource, setFailedSource] = useState('');
  const input = useRef<HTMLInputElement>(null);
  const helpId = useId();
  useEffect(() => {
    if (!file) { setPreview(''); if (input.current) input.current.value = ''; return; }
    const url = URL.createObjectURL(file);
    setPreview(url);
    return () => URL.revokeObjectURL(url);
  }, [file]);
  const source = preview || imageUrl;
  return <div className={'myos-image-picker' + (compact ? ' myos-image-picker-compact' : '')}>
    <div className={'myos-image-preview' + (avatar ? ' myos-image-preview-avatar' : '')}>
      {source && source !== failedSource ? <img src={source} alt={alt} width={128} height={128} onError={() => setFailedSource(source)} /> : <div className="myos-image-placeholder">
        <svg width="36" height="36" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          {avatar ? <><circle cx="12" cy="8" r="4" /><path d="M4 21v-2a8 8 0 0 1 16 0v2" /></> : <><rect x="3" y="3" width="18" height="18" rx="3" /><circle cx="8.5" cy="8.5" r="1.5" /><path d="m21 15-5-5L5 21" /></>}
        </svg>
        {!compact && <span>{avatar ? 'No photo' : 'No image'}</span>}
      </div>}
    </div>
    <div className="myos-image-controls">
      <input className="myos-image-input" ref={input} type="file" hidden accept="image/jpeg,image/png" aria-label={label} aria-describedby={helpId} disabled={disabled} onChange={event => choose(event.target.files?.[0] ?? null)} />
      <div className="myos-image-selection">
        <button type="button" className="button secondary" aria-describedby={helpId} disabled={disabled} onClick={() => input.current?.click()}>{label}</button>
        {file && <span className="myos-image-filename">{file.name}</span>}
      </div>
      <p id={helpId} className="myos-image-help">JPEG or PNG, up to 5 MiB.</p>
      {children && <div className="myos-image-actions">{children}</div>}
    </div>
  </div>;
}
