import { expect, test } from '@playwright/test';
import { signInViaApi } from './helpers';

test('a visitor is sent to the login page with the way back', async ({ page }) => {
  await page.goto('/?tab=history');

  await expect(page).toHaveURL((url) => url.pathname === '/login' && url.searchParams.get('next') === '/?tab=history');
  await expect(page.getByRole('heading', { name: 'Вход' })).toBeVisible();
});

test('a failure of /api/auth/me shows the error page, and retry recovers', async ({ page }) => {
  await page.route('**/api/auth/me', (route) => route.fulfill({ status: 503, body: 'Service Unavailable' }));

  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Что-то пошло не так' })).toBeVisible();

  await page.unroute('**/api/auth/me');
  await page.getByRole('button', { name: 'Повторить' }).click();

  await expect(page).toHaveURL((url) => url.pathname === '/login');
});

test('a signed-in user gets the page and the name from the session', async ({ page }) => {
  await signInViaApi(page, 'north.initiator');

  await page.goto('/');

  await expect(page.getByRole('heading', { name: 'Здравствуйте, Инициатор (Север)' })).toBeVisible();
});

test('a signed-in user on /login goes on to next, but never to another site', async ({ page }) => {
  await signInViaApi(page, 'north.initiator');

  await page.goto('/login?next=%2F%3Ftab%3Dx');
  await expect(page).toHaveURL((url) => url.pathname === '/' && url.search === '?tab=x');

  await page.goto('/login?next=https%3A%2F%2Fevil.example%2F');
  await expect(page).toHaveURL((url) => url.origin !== 'https://evil.example' && url.pathname === '/');
});
