import { expect, test, type Page } from '@playwright/test';

// The data layer against the real dispatch, inside the browser: real cookies, real CSRF rotation.
// The module is loaded straight from the Vite dev server, so this runs only without WEB_BASE_URL,
// and needs dispatch on :8080 (make up or make run).
const DEMO_PASSWORD = 'demo1234';

test.skip(!!process.env.WEB_BASE_URL, 'loads source modules from the Vite dev server');

test.beforeEach(async ({ page, request }) => {
  const info = await request.get('/api/system/info').catch(() => undefined);
  test.skip(!info?.ok(), 'dispatch is not reachable through the dev server proxy');
  await page.goto('/');
});

/** Runs `body` in the page with the data layer module (`@/shared/api`) as `api`. */
function withApi<T>(page: Page, body: string): Promise<T> {
  return page.evaluate(
    `(async () => { const m = await import('/src/shared/api/index.ts'); ${body} })()`,
  ) as Promise<T>;
}

test('signs in without a CSRF cookie: the token is fetched first', async ({ page }) => {
  expect(await page.evaluate(() => document.cookie)).not.toContain('XSRF-TOKEN');

  const me = await withApi<{ username: string }>(
    page,
    `await m.unwrap(m.api.POST('/api/auth/login', { body: { username: 'north.initiator', password: '${DEMO_PASSWORD}' } }));
     return m.unwrap(m.api.GET('/api/auth/me'));`,
  );

  expect(me.username).toBe('north.initiator');
});

test('repeats a call rejected for a stale CSRF token and maps 401 to ApiError', async ({ page }) => {
  const result = await withApi<{ logout: number; code: string; status: number; isApiError: boolean }>(
    page,
    `await m.unwrap(m.api.POST('/api/auth/login', { body: { username: 'north.initiator', password: '${DEMO_PASSWORD}' } }));
     document.cookie = 'XSRF-TOKEN=stale; path=/';
     const { response } = await m.api.POST('/api/auth/logout');
     const error = await m.unwrap(m.api.GET('/api/auth/me')).catch((e) => e);
     return { logout: response.status, code: error.code, status: error.status, isApiError: error instanceof m.ApiError };`,
  );

  expect(result).toEqual({ logout: 204, code: 'UNAUTHENTICATED', status: 401, isApiError: true });
});
