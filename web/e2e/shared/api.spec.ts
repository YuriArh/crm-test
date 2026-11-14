import { expect, test, type Page } from '@playwright/test';

// The data layer against the real dispatch, inside the browser: real cookies, real CSRF checks.
// The module is loaded straight from the Vite dev server, so this runs only without WEB_BASE_URL.
// dispatch must be running (make up or make run): an unreachable dispatch fails these tests.
const DEMO_PASSWORD = 'demo1234';

test.skip(!!process.env.WEB_BASE_URL, 'loads source modules from the Vite dev server');

test.beforeEach(async ({ page, request }) => {
  const info = await request.get('/api/system/info').catch(() => undefined);
  expect(info?.ok(), 'dispatch must be reachable through the dev server proxy (make up)').toBe(true);
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

test('repeats a call that dispatch rejects because the CSRF cookie went away in flight', async ({ page }) => {
  // The first POST loses its cookie after the client has read it (as after logout in another tab), so
  // dispatch answers 403 CSRF_INVALID; the client must ask for a new token and send the call again.
  const log = await withApi<string[]>(
    page,
    `const log = [];
     let dropCookie = true;
     const client = m.createApiClient({
       baseUrl: location.origin,
       fetch: async (request) => {
         if (request.method === 'POST' && dropCookie) {
           dropCookie = false;
           document.cookie = 'XSRF-TOKEN=; path=/; max-age=0';
         }
         const response = await fetch(request);
         log.push(request.method + ' ' + new URL(request.url).pathname + ' ' + response.status);
         return response;
       },
     });
     await m.unwrap(client.POST('/api/auth/login', { body: { username: 'north.initiator', password: '${DEMO_PASSWORD}' } }));
     return log;`,
  );

  expect(log).toEqual([
    'GET /api/auth/csrf 200',
    'POST /api/auth/login 403',
    'GET /api/auth/csrf 200',
    'POST /api/auth/login 200',
  ]);
});

test('maps 401 after logout to ApiError UNAUTHENTICATED', async ({ page }) => {
  const result = await withApi<{ logout: number; code: string; status: number; isApiError: boolean }>(
    page,
    `await m.unwrap(m.api.POST('/api/auth/login', { body: { username: 'north.initiator', password: '${DEMO_PASSWORD}' } }));
     const { response } = await m.api.POST('/api/auth/logout');
     const error = await m.unwrap(m.api.GET('/api/auth/me')).catch((e) => e);
     return { logout: response.status, code: error.code, status: error.status, isApiError: error instanceof m.ApiError };`,
  );

  expect(result).toEqual({ logout: 204, code: 'UNAUTHENTICATED', status: 401, isApiError: true });
});
