import { expect, test } from '@playwright/test';

test('an unknown address shows the not-found page with a way home', async ({ page }) => {
  await page.goto('/no-such-page');

  await expect(page.getByRole('heading', { name: 'Страница не найдена' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'На главную' })).toHaveAttribute('href', '/');
});
