import { Link } from 'react-router';
import { Card, Page, PageHeader } from '../../shared/design/Layout';
import type { NavEntry } from '../../shared/layout/AppShell';
import styles from './OverviewPage.module.css';

/** A placeholder until there are aggregate endpoints (NFR-09): shortcuts to what the operator may open. */
export function OverviewPage({ shortcuts }: { shortcuts: readonly NavEntry[] }) {
  return (
    <Page>
      <PageHeader title="Visão geral" subtitle="Métricas de receita e da base chegam em breve. Por enquanto, vá direto ao que precisa." />
      <div className={styles.grid}>
        {shortcuts.map((entry) => (
          <Link key={entry.to} to={entry.to} className={styles.shortcut}>
            <Card title={entry.label}><span className={styles.go}>Abrir {entry.label.toLowerCase()} →</span></Card>
          </Link>
        ))}
      </div>
    </Page>
  );
}
