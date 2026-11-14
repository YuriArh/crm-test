import { expect, test, type Page } from '@playwright/test';
import { DEMO_PASSWORD } from './helpers';

async function signInWithForm(page: Page, username: string, password: string) {
  await page.getByLabel('Логин').fill(username);
  await page.getByLabel('Пароль').fill(password);
  await page.getByRole('button', { name: 'Войти' }).click();
}

test('signs in with the form and lands on the registry', async ({ page }) => {
  await page.goto('/login');

  await signInWithForm(page, 'north.initiator', DEMO_PASSWORD);

  await expect(page.getByRole('heading', { name: 'Заявки' })).toBeVisible();
  await expect(page).toHaveURL((url) => url.pathname === '/requests');
});

test('a wrong password keeps the user on the login page with a message', async ({ page }) => {
  await page.goto('/login');

  await signInWithForm(page, 'north.initiator', 'wrong-password');

  await expect(page.getByRole('alert')).toHaveText('Неверный логин или пароль.');
  await expect(page).toHaveURL((url) => url.pathname === '/login');
  await expect(page.getByLabel('Пароль')).toHaveValue('');
});

test('a deep link survives the detour through the login page', async ({ page }) => {
  await page.goto('/requests?result=REJECTED');
  await expect(page).toHaveURL((url) => url.pathname === '/login');

  await signInWithForm(page, 'south.initiator', DEMO_PASSWORD);

  await expect(page.getByRole('heading', { name: 'Заявки' })).toBeVisible();
  await expect(page.getByRole('banner').getByText('Инициатор (Юг)')).toBeVisible();
  await expect(page).toHaveURL((url) => url.pathname === '/requests' && url.search === '?result=REJECTED');
});

test('next pointing to another site lands on the registry instead', async ({ page }) => {
  await page.goto('/login?next=https%3A%2F%2Fevil.example%2F');

  await signInWithForm(page, 'north.initiator', DEMO_PASSWORD);

  await expect(page.getByRole('heading', { name: 'Заявки' })).toBeVisible();
  expect(new URL(page.url()).origin).not.toBe('https://evil.example');
});
