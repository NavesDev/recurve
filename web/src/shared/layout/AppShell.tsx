import type { ReactNode } from 'react';
import { NavLink } from 'react-router';
import type { Permission } from '../constants/permissions';
import { Icon, Logo, type IconName } from '../design/Icon';
import styles from './AppShell.module.css';

export interface NavEntry {
  to: string;
  label: string;
  icon: IconName;
  /** Needed to see the entry; absent, every signed-in operator sees it. */
  permission?: Permission;
}

interface AppShellProps {
  nav: readonly NavEntry[];
  operator: { name: string; email: string; initials: string };
  section?: string;
  onSignOut: () => void;
  children: ReactNode;
}

/** The prototype's frame: sidebar, top bar, content. Knows no feature; it is handed what to show. */
export function AppShell({ nav, operator, section, onSignOut, children }: AppShellProps) {
  return (
    <div className={styles.shell}>
      <aside className={styles.sidebar}>
        <span className={styles.brand}><Logo /> Recurve</span>
        <nav aria-label="Principal" className={styles.nav}>
          {nav.map((entry) => (
            <NavLink key={entry.to} to={entry.to} end={entry.to === '/'} className={styles.link}>
              <Icon name={entry.icon} />
              {entry.label}
            </NavLink>
          ))}
        </nav>
      </aside>
      <div className={styles.main}>
        <header className={styles.topbar}>
          <span className={styles.crumb}>Recurve{section && <> / <strong>{section}</strong></>}</span>
          <div className={styles.account}>
            <div className={styles.who}>
              <span className={styles.name}>{operator.name}</span>
              <span className={styles.email}>{operator.email}</span>
            </div>
            <span className={styles.avatar} aria-hidden="true">{operator.initials}</span>
            <button type="button" className={styles.signOut} onClick={onSignOut}>
              <Icon name="signOut" size={16} /> Sair
            </button>
          </div>
        </header>
        <main className={styles.content}>{children}</main>
      </div>
    </div>
  );
}
