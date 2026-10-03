import { useEffect, useId, useRef, useState } from 'react';
import styles from './Menu.module.css';

export interface MenuItem {
  label: string;
  onSelect: () => void;
  tone?: 'danger';
}

interface MenuProps {
  /** Accessible name of the trigger, e.g. "Ações de Pro". */
  label: string;
  items: readonly MenuItem[];
}

/** A row's actions behind "…". With no action to offer, there is no menu at all. */
export function Menu({ label, items }: MenuProps) {
  const [open, setOpen] = useState(false);
  const menuId = useId();
  const wrapper = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) return;
    wrapper.current?.querySelector<HTMLElement>('[role="menuitem"]')?.focus();
    function onPointer(event: MouseEvent) {
      if (!wrapper.current?.contains(event.target as Node)) setOpen(false);
    }
    function onKey(event: KeyboardEvent) {
      if (event.key === 'Escape') { setOpen(false); trigger.current?.focus(); }
      if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
        const entries = [...(wrapper.current?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? [])];
        const at = entries.indexOf(document.activeElement as HTMLElement);
        const next = event.key === 'ArrowDown' ? (at + 1) % entries.length : (at - 1 + entries.length) % entries.length;
        entries[next]?.focus();
        event.preventDefault();
      }
    }
    document.addEventListener('mousedown', onPointer);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onPointer);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  if (items.length === 0) return null;
  return (
    <div ref={wrapper} className={styles.wrapper}>
      <button
        ref={trigger} type="button" className={styles.trigger} aria-label={label}
        aria-haspopup="menu" aria-expanded={open} aria-controls={open ? menuId : undefined}
        onClick={() => setOpen((value) => !value)}
      >
        <svg width="16" height="16" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
          <circle cx="3" cy="8" r="1.4" /><circle cx="8" cy="8" r="1.4" /><circle cx="13" cy="8" r="1.4" />
        </svg>
      </button>
      {open && (
        <div id={menuId} role="menu" aria-label={label} className={styles.menu}>
          <span className={styles.heading} aria-hidden="true">Ações</span>
          {items.map((item) => (
            <button
              key={item.label} type="button" role="menuitem" tabIndex={-1}
              className={`${styles.item} ${item.tone === 'danger' ? styles.danger : ''}`}
              onClick={() => { setOpen(false); item.onSelect(); }}
            >
              {item.label}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
