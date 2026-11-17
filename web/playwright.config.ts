import { defineConfig, devices } from '@playwright/test';

// Against a running stack: WEB_BASE_URL=http://localhost:3000 pnpm e2e.
// Without it, Playwright starts the Vite dev server itself (API calls are proxied to dispatch on :8080).
const externalBaseUrl = process.env.WEB_BASE_URL;
const baseURL = externalBaseUrl ?? 'http://localhost:5173';
const FAULTS = '**/problems/faults.spec.ts';

export default defineConfig({
  // One folder per application module (e2e/<module>/*.spec.ts); e2e/app/ covers the app shell.
  testDir: './e2e',
  testMatch: '**/*.spec.ts',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? 'line' : 'list',
  use: {
    baseURL,
    trace: 'retain-on-failure',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] }, testIgnore: FAULTS },
    // The emulator's fault switches are global: while one is on, every new request is affected. These tests run
    // after all the others, one at a time (e2e/problems/faults.spec.ts).
    {
      name: 'faults',
      use: { ...devices['Desktop Chrome'] },
      testMatch: FAULTS,
      dependencies: ['chromium'],
      fullyParallel: false,
    },
  ],
  webServer: externalBaseUrl
    ? undefined
    : {
        command: 'pnpm dev --strictPort',
        url: baseURL,
        reuseExistingServer: !process.env.CI,
      },
});
