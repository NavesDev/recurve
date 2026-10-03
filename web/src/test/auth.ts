import { http, HttpResponse } from 'msw';
import { sessionStore } from '../shared/api/session';
import { server } from './server';

/** A signed-in operator holding exactly these permissions (already expanded, as /api/me answers). */
export function signInAs(permissions: string[], name = 'Joana Martins') {
  sessionStore.set({ token: 'test-token', expiresAt: new Date(Date.now() + 3_600_000).toISOString() });
  server.use(http.get('/api/me', () => HttpResponse.json({ id: 'me', name, email: 'joana@recurve.app', permissions })));
}
