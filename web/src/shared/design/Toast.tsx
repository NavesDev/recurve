import { createContext, useCallback, useContext, useMemo, useRef, useState, type ReactNode } from 'react';
import styles from './Toast.module.css';

type Kind = 'success' | 'error';

interface ToastEntry {
  id: number;
  kind: Kind;
  message: string;
}

interface ToastApi {
  success: (message: string) => void;
  error: (message: string) => void;
}

const ToastContext = createContext<ToastApi | null>(null);
const LIFETIME_MS = 5000;

export function ToastProvider({ children }: { children: ReactNode }) {
  const [entries, setEntries] = useState<ToastEntry[]>([]);
  const nextId = useRef(0);

  const dismiss = useCallback((id: number) => setEntries((all) => all.filter((entry) => entry.id !== id)), []);
  const show = useCallback((kind: Kind, message: string) => {
    const id = nextId.current++;
    setEntries((all) => [...all, { id, kind, message }]);
    setTimeout(() => dismiss(id), LIFETIME_MS);
  }, [dismiss]);

  const api = useMemo<ToastApi>(() => ({
    success: (message) => show('success', message),
    error: (message) => show('error', message),
  }), [show]);

  return (
    <ToastContext.Provider value={api}>
      {children}
      <div className={styles.region} role="status" aria-live="polite">
        {entries.map((entry) => (
          <div key={entry.id} className={styles.surface}>
            <div className={`${styles.toast} ${styles[entry.kind]}`}>
              <span className={styles.message}>{entry.message}</span>
              <button type="button" className={styles.close} aria-label="Fechar aviso" onClick={() => dismiss(entry.id)}>×</button>
            </div>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

export function useToast(): ToastApi {
  const api = useContext(ToastContext);
  if (!api) throw new Error('useToast needs a ToastProvider above it');
  return api;
}
