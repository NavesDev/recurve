import type { ReactNode } from 'react';
import styles from './StatusScreen.module.css';

export function StatusScreen({ code, title, text, action }: { code?: string; title: string; text?: string; action?: ReactNode }) {
  return (
    <div className={styles.screen}>
      {code && <span className={styles.code}>{code}</span>}
      <h1 className={styles.title}>{title}</h1>
      {text && <p className={styles.text}>{text}</p>}
      {action}
    </div>
  );
}

export function LoadingScreen() {
  return (
    <div className={styles.screen} role="status" aria-live="polite">
      <span className={styles.spinner} aria-hidden="true" />
      <span className="visually-hidden">Carregando…</span>
    </div>
  );
}
