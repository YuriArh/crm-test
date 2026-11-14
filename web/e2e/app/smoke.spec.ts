import { expect, test } from '@playwright/test';
import { collectConsoleErrors } from '../auth/helpers';

test('the app starts without errors', async ({ page }) => {
  const errors = collectConsoleErrors(page);

  await page.goto('/');

  await expect(page).toHaveTitle('Dispatch');
  await expect(page.getByRole('heading', { name: 'Вход' })).toBeVisible();
  expect(errors).toEqual([]);
});
