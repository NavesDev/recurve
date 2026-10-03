import { SESSION_STORAGE_KEY } from '../constants/api';

export interface Session {
  token: string;
  expiresAt: string;
}

export interface SessionStore {
  get(): Session | null;
  set(session: Session): void;
  clear(): void;
  /** Called on every change; returns the unsubscribe function. */
  subscribe(listener: () => void): () => void;
}

function isSession(value: unknown): value is Session {
  return typeof value === 'object' && value !== null
    && typeof (value as Session).token === 'string'
    && typeof (value as Session).expiresAt === 'string';
}

/**
 * The signed-in operator's token: in memory, mirrored to sessionStorage so
 * a reload keeps it and closing the tab ends it. Fail-closed — storage
 * that is unreadable, malformed or expired reads as signed out, and a
 * storage that throws only costs the reload, never the session.
 */
export function createSessionStore(now: () => number = Date.now): SessionStore {
  const listeners = new Set<() => void>();
  let current: Session | null = read();

  function read(): Session | null {
    try {
      const raw = sessionStorage.getItem(SESSION_STORAGE_KEY);
      const parsed: unknown = raw ? JSON.parse(raw) : null;
      return isSession(parsed) ? parsed : null;
    } catch {
      return null;
    }
  }

  function write(session: Session | null) {
    try {
      if (session) sessionStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(session));
      else sessionStorage.removeItem(SESSION_STORAGE_KEY);
    } catch {
      // Storage blocked: the session lives in memory only.
    }
  }

  function notify() {
    listeners.forEach((listener) => listener());
  }

  return {
    get() {
      if (current && Date.parse(current.expiresAt) <= now()) {
        current = null;
        write(null);
      }
      return current;
    },
    set(session) {
      current = session;
      write(session);
      notify();
    },
    clear() {
      current = null;
      write(null);
      notify();
    },
    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
  };
}

export const sessionStore = createSessionStore();
