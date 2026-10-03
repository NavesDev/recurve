import { defineConfig, devices } from '@playwright/test';

/**
 * Journeys against the real server: start it first (see README), then
 * `E2E_EMAIL=… E2E_PASSWORD=… npm run e2e`. The operator must hold every
 * permission. Vite is started here, proxying to API_TARGET.
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: 'http://localhost:5174',
    trace: 'retain-on-failure',
    locale: 'pt-BR',
    timezoneId: 'America/Sao_Paulo',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'npx vite --port 5174 --strictPort',
    url: 'http://localhost:5174',
    reuseExistingServer: false,
    env: { API_TARGET: process.env.API_TARGET ?? 'http://localhost:8080' },
  },
});
