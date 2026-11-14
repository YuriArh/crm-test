import { expect, test } from '@playwright/test';
import { signInViaApi } from './helpers';

test('the header shows the operator and their service', async ({ page }) => {
  await signInViaApi(page, 'north.operator');

  await page.goto('/');

  const header = page.getByRole('banner');
  await expect(header.getByText('Оператор (Север)')).toBeVisible();
  await expect(header.getByText('Оператор · Служба Север')).toBeVisible();
});

test('sign-out leads to the login page, and Back does not bring the page back', async ({ page }) => {
  await signInViaApi(page, 'north.initiator');
  await page.goto('/');
  await expect(page.getByRole('heading', { name: /Здравствуйте/ })).toBeVisible();

  await page.getByRole('button', { name: 'Выйти' }).click();
  await expect(page.getByRole('heading', { name: 'Вход' })).toBeVisible();

  await page.goBack();
  await expect(page).toHaveURL((url) => url.pathname === '/login');
  await expect(page.getByRole('heading', { name: 'Вход' })).toBeVisible();
  await expect(page.getByRole('heading', { name: /Здравствуйте/ })).toBeHidden();

  const me = await page.request.get('/api/auth/me');
  expect(me.status()).toBe(401);
});
