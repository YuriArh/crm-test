import { defineConfig, devices } from '@playwright/test';

/**
 * Browser scenarios against the running Docker stack (`make up`, then `make web-e2e`).
 * They create their own data and must pass repeatedly on a non-empty database.
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  retries: 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: process.env.WEB_BASE_URL ?? 'http://localhost:3000',
    locale: 'ru-RU',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
