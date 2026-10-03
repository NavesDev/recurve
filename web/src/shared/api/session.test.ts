import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createSessionStore } from './session';

const NOW = new Date('2026-01-15T10:00:00Z').getTime();
const later = '2026-01-15T18:00:00Z';
const earlier = '2026-01-15T09:00:00Z';

describe('session store', () => {
  beforeEach(() => sessionStorage.clear());

  it('keeps a session in memory and in sessionStorage', () => {
    const store = createSessionStore(() => NOW);
    store.set({ token: 'abc', expiresAt: later });

    expect(store.get()).toEqual({ token: 'abc', expiresAt: later });
    expect(createSessionStore(() => NOW).get()).toEqual({ token: 'abc', expiresAt: later });
  });

  it('forgets an expired session (fail-closed)', () => {
    sessionStorage.setItem('recurve.session', JSON.stringify({ token: 'abc', expiresAt: earlier }));

    expect(createSessionStore(() => NOW).get()).toBeNull();
    expect(sessionStorage.getItem('recurve.session')).toBeNull();
  });

  it('treats unreadable storage as signed out (fail-closed)', () => {
    sessionStorage.setItem('recurve.session', '{not json');
    expect(createSessionStore(() => NOW).get()).toBeNull();

    sessionStorage.setItem('recurve.session', JSON.stringify({ token: 42 }));
    expect(createSessionStore(() => NOW).get()).toBeNull();
  });

  it('clears and tells subscribers', () => {
    const store = createSessionStore(() => NOW);
    const listener = vi.fn();
    store.subscribe(listener);
    store.set({ token: 'abc', expiresAt: later });
    store.clear();

    expect(store.get()).toBeNull();
    expect(sessionStorage.getItem('recurve.session')).toBeNull();
    expect(listener).toHaveBeenCalledTimes(2);
  });

  it('still works when sessionStorage throws', () => {
    const spy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    const store = createSessionStore(() => NOW);
    store.set({ token: 'abc', expiresAt: later });

    expect(store.get()).toEqual({ token: 'abc', expiresAt: later });
    spy.mockRestore();
  });
});
