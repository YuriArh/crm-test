import { expect, test } from '@playwright/test';
import { signInViaApi } from './helpers';

// The session disappears on the server side (sign-out elsewhere, dispatch restarted): here the
// session cookie is dropped. Until screens with data exist, the next call is the sign-out button;
// the redirect with `next` from any 401 is covered by src/app/sessionLost.test.tsx.

test('after the session is lost, the next call leads to the login page', async ({ page }) => {
  await signInViaApi(page, 'north.initiator');
  await page.goto('/');
  await expect(page.getByRole('heading', { name: /Здравствуйте/ })).toBeVisible();

  await page.context().clearCookies({ name: 'JSESSIONID' });
  const logout = page.waitForResponse('**/api/auth/logout');
  await page.getByRole('button', { name: 'Выйти' }).click();

  expect((await logout).status()).toBe(401);
  await expect(page.getByRole('heading', { name: 'Вход' })).toBeVisible();
});

test('after the session is lost, reloading a page leads to the login page with the way back', async ({ page }) => {
  await signInViaApi(page, 'north.initiator');
  await page.goto('/?tab=history');
  await expect(page.getByRole('heading', { name: /Здравствуйте/ })).toBeVisible();

  await page.context().clearCookies({ name: 'JSESSIONID' });
  await page.reload();

  await expect(page).toHaveURL((url) => url.pathname === '/login' && url.searchParams.get('next') === '/?tab=history');
});
