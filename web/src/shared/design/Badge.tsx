import styles from './Badge.module.css';

export type Tone = 'action' | 'success' | 'warn' | 'danger' | 'neutral';

export function Badge({ tone, children }: { tone: Tone; children: React.ReactNode }) {
  return <span className={`${styles.badge} ${styles[tone]}`}>{children}</span>;
}
