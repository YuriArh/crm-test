import { expect, test } from '@playwright/test';

test('the app starts without errors', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text());
  });

  await page.goto('/');

  await expect(page).toHaveTitle('Dispatch');
  await expect(page.locator('#root')).toBeAttached();
  expect(errors).toEqual([]);
});
