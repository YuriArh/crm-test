import { expect, test } from '@playwright/test';

const scheme = (page: import('@playwright/test').Page) =>
  page.evaluate(() => document.documentElement.getAttribute('data-mantine-color-scheme'));

test('the app is dark by default, and the light scheme survives a reload', async ({ page }) => {
  await page.goto('/login');
  await expect(page.getByRole('button', { name: 'Войти' })).toBeVisible();
  expect(await scheme(page)).toBe('dark');

  await page.getByRole('button', { name: 'Светлая тема' }).click();
  expect(await scheme(page)).toBe('light');

  await page.reload();
  await expect(page.getByRole('button', { name: 'Тёмная тема' })).toBeVisible();
  expect(await scheme(page)).toBe('light');
});

test('the saved scheme is applied before the app starts (no flash of the other scheme)', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('mantine-color-scheme-value', 'light'));
  // Stop scripts of the app from running: only index.html's inline script may set the scheme.
  // (dev server: /src/main.tsx?t=…, Docker: /assets/index-<hash>.js).
  await page.route(/\/src\/main\.tsx|\/assets\/.+\.js/, (route) => route.abort());

  await page.goto('/login');

  expect(await scheme(page)).toBe('light');
  await expect(page.getByRole('button', { name: 'Войти' })).toHaveCount(0);
});
