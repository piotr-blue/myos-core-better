import { useEffect, useId, useRef, type ReactNode } from "react";
import { X } from "lucide-react";
export function Dialog({
  title,
  onClose,
  children,
  headerActions,
  className = "",
}: {
  title: string;
  onClose?: () => void;
  children: ReactNode;
  headerActions?: ReactNode;
  className?: string;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const id = useId();
  useEffect(() => {
    ref.current?.showModal();
    return () => ref.current?.close();
  }, []);
  const close = () => {
    if (!onClose) return;
    ref.current?.close();
    onClose();
  };
  return (
    <dialog
      ref={ref}
      className={`dialog ${className}`}
      aria-labelledby={id}
      onCancel={(event) => {
        event.preventDefault();
        close();
      }}
    >
      <div className="dialog-header">
        <h2 id={id}>{title}</h2>
        {headerActions}
        {onClose && (
          <button
            className="plain-icon"
            aria-label="Close dialog"
            onClick={close}
          >
            <X size={20} />
          </button>
        )}
      </div>
      {children}
    </dialog>
  );
}
