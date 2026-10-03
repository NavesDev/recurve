import type { ReactNode } from 'react';
import { Button } from './Button';
import { Dialog } from './Dialog';

interface ConfirmDialogProps {
  open: boolean;
  title: string;
  confirmLabel: string;
  tone?: 'primary' | 'danger';
  busy?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
  children: ReactNode;
}

/** Asks before an action that cannot be taken back. Never window.confirm. */
export function ConfirmDialog({ open, title, confirmLabel, tone = 'primary', busy, onConfirm, onCancel, children }: ConfirmDialogProps) {
  return (
    <Dialog
      open={open} title={title} onClose={busy ? () => {} : onCancel}
      actions={
        <>
          <Button variant="secondary" onClick={onCancel} disabled={busy}>Voltar</Button>
          <Button variant={tone} onClick={onConfirm} busy={busy}>{confirmLabel}</Button>
        </>
      }
    >
      {children}
    </Dialog>
  );
}
