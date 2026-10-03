import type { InputHTMLAttributes, ReactNode } from 'react';
import { Link } from 'react-router';
import styles from './Layout.module.css';

export function Card({ title, actions, children }: { title?: string; actions?: ReactNode; children: ReactNode }) {
  return (
    <section className={styles.card} aria-label={title}>
      {(title || actions) && (
        <div className={styles.cardHeader}>
          {title && <h2 className={styles.cardTitle}>{title}</h2>}
          {actions}
        </div>
      )}
      {children}
    </section>
  );
}

interface PageHeaderProps {
  title: string;
  subtitle?: ReactNode;
  back?: { to: string; label: string };
  actions?: ReactNode;
}

export function PageHeader({ title, subtitle, back, actions }: PageHeaderProps) {
  return (
    <header className={styles.header}>
      <div className={styles.headings}>
        {back && <Link className={styles.back} to={back.to}>‹ {back.label}</Link>}
        <h1 className={styles.title}>{title}</h1>
        {subtitle && <p className={styles.subtitle}>{subtitle}</p>}
      </div>
      {actions && <div className={styles.actions}>{actions}</div>}
    </header>
  );
}

export function Page({ children }: { children: ReactNode }) {
  return <div className={styles.page}>{children}</div>;
}

export function Toolbar({ children }: { children: ReactNode }) {
  return <div className={styles.toolbar}>{children}</div>;
}

export function SearchInput(props: InputHTMLAttributes<HTMLInputElement> & { label: string }) {
  const { label, ...rest } = props;
  return <input type="search" aria-label={label} className={styles.search} {...rest} />;
}

export function EmptyState({ title, text, action }: { title: string; text?: string; action?: ReactNode }) {
  return (
    <div className={styles.empty}>
      <h2 className={styles.emptyTitle}>{title}</h2>
      {text && <p className={styles.emptyText}>{text}</p>}
      {action}
    </div>
  );
}

export function Details({ items }: { items: readonly { term: string; value: ReactNode }[] }) {
  return (
    <dl className={styles.details}>
      {items.map((item) => (
        <div key={item.term}>
          <dt className={styles.term}>{item.term}</dt>
          <dd className={styles.definition}>{item.value}</dd>
        </div>
      ))}
    </dl>
  );
}

export function FormActions({ children }: { children: ReactNode }) {
  return <div className={styles.formActions}>{children}</div>;
}

export function Cell({ main, sub }: { main: ReactNode; sub?: ReactNode }) {
  return (
    <div className={styles.cell}>
      <span className={styles.cellMain}>{main}</span>
      {sub && <span className={styles.cellSub}>{sub}</span>}
    </div>
  );
}

export function Mono({ children }: { children: ReactNode }) {
  return <span className={styles.mono}>{children}</span>;
}

export function Alert({ tone = 'danger', children }: { tone?: 'danger' | 'warn'; children: ReactNode }) {
  return <div role="alert" className={`${styles.alert} ${tone === 'warn' ? styles.warnAlert : ''}`}>{children}</div>;
}
