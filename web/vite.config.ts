import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  plugins: [react()],
  server: {
    // Same origin in development: the server needs no CORS (NFR-10). The
    // Host header must reach the server unchanged — rewritten to the
    // target's, the browser's Origin would look foreign and be refused.
    proxy: { '/api': { target: process.env.API_TARGET ?? 'http://localhost:8080', changeOrigin: false } },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}', 'eslint/**/*.test.js'],
    css: { modules: { classNameStrategy: 'non-scoped' } },
  },
});
