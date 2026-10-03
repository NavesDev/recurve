import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterAll, afterEach, beforeAll } from 'vitest';
import { sessionStore } from '../shared/api/session';
import { server } from './server';

// A request no test declared fails the test: nothing reaches a real network.
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  // Vitest runs without globals, so Testing Library cannot register this itself.
  cleanup();
  server.resetHandlers();
  sessionStore.clear();
  sessionStorage.clear();
});
afterAll(() => server.close());
