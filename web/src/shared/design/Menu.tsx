import { useEffect, useId, useLayoutEffect, useRef, useState, type CSSProperties } from 'react';
import { createPortal } from 'react-dom';
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

const GAP = 6;

/**
 * A row's actions behind "…". With no action to offer, there is no menu at all.
 * The menu floats in a portal, fixed to the trigger: inside a table's scrolling
 * frame an absolute menu would be clipped and stretch the scroll area.
 */
export function Menu({ label, items }: MenuProps) {
  const [open, setOpen] = useState(false);
  const menuId = useId();
  const wrapper = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const menu = useRef<HTMLDivElement>(null);
  const [place, setPlace] = useState<CSSProperties>({ position: 'fixed', visibility: 'hidden' });

  useLayoutEffect(() => {
    if (!open) return;
    function anchor() {
      const at = trigger.current?.getBoundingClientRect();
      if (!at) return;
      const height = menu.current?.offsetHeight ?? 0;
      const below = at.bottom + GAP + height <= window.innerHeight || at.top - GAP - height < 0;
      setPlace({
        position: 'fixed',
        right: window.innerWidth - at.right,
        ...(below ? { top: at.bottom + GAP } : { bottom: window.innerHeight - at.top + GAP }),
      });
    }
    anchor();
    window.addEventListener('scroll', anchor, true);
    window.addEventListener('resize', anchor);
    return () => {
      window.removeEventListener('scroll', anchor, true);
      window.removeEventListener('resize', anchor);
    };
  }, [open]);

  useEffect(() => {
    if (!open) return;
    menu.current?.querySelector<HTMLElement>('[role="menuitem"]')?.focus();
    function onPointer(event: MouseEvent) {
      const target = event.target as Node;
      if (!wrapper.current?.contains(target) && !menu.current?.contains(target)) setOpen(false);
    }
    function onKey(event: KeyboardEvent) {
      if (event.key === 'Escape') { setOpen(false); trigger.current?.focus(); }
      if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
        const entries = [...(menu.current?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? [])];
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
      {open && createPortal(
        <div ref={menu} id={menuId} role="menu" aria-label={label} className={styles.menu} style={place}>
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
        </div>,
        document.body,
      )}
    </div>
  );
}
