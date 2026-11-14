import { expect, type Page } from '@playwright/test';

export const DEMO_PASSWORD = 'demo1234';

/**
 * Signs in through the API with the page's own cookie jar, the way the app would, so the next page
 * load is already signed in. For tests that are not about the login form.
 */
export async function signInViaApi(page: Page, username: string): Promise<void> {
  const csrf = await page.request.get('/api/auth/csrf');
  expect(csrf.ok()).toBe(true);
  const { headerName, token } = (await csrf.json()) as { headerName: string; token: string };
  const login = await page.request.post('/api/auth/login', {
    headers: { [headerName]: token },
    data: { username, password: DEMO_PASSWORD },
  });
  expect(login.ok()).toBe(true);
}

/** Console errors, minus the browser's own line for the expected `401` of `/api/auth/me` (nobody signed in). */
export function collectConsoleErrors(page: Page): string[] {
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  page.on('console', (message) => {
    if (message.type() !== 'error') return;
    const anonymousMe = message.location().url.endsWith('/api/auth/me') && message.text().includes('401');
    if (!anonymousMe) errors.push(message.text());
  });
  return errors;
}
