import { expect, test, type Page } from '@playwright/test';
import { collectConsoleErrors, signInViaApi } from '../auth/helpers';

async function expectNoHorizontalScroll(page: Page) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow).toBeLessThanOrEqual(0);
}

test('an operator opens the problems of the service', async ({ page }) => {
  const errors = collectConsoleErrors(page);
  await signInViaApi(page, 'north.operator');

  await page.goto('/problems');

  await expect(page.getByRole('heading', { name: 'Проблемы', level: 2 })).toBeVisible();
  await expect(page.getByTestId('problem-total')).toBeVisible();
  await expect(page.getByRole('radio', { name: 'Открытые' })).toBeChecked();
  expect(errors).toEqual([]);
});

test('an initiator has no problems page, even by a direct link', async ({ page }) => {
  await signInViaApi(page, 'north.initiator');

  await page.goto('/requests');
  await expect(page.getByRole('heading', { name: 'Заявки', level: 2 })).toBeVisible();
  await expect(page.getByRole('navigation', { name: 'Разделы' })).toHaveCount(0);

  await page.goto('/problems');
  await expect(page.getByRole('heading', { name: 'Страница не найдена' })).toBeVisible();
  await page.goto('/problems/0195f3a2-0000-7000-8000-000000000001');
  await expect(page.getByRole('heading', { name: 'Страница не найдена' })).toBeVisible();
});

test.describe('on a phone', () => {
  test.use({ viewport: { width: 390, height: 844 } });

  test('the problems list fits the screen and its filters are in a sheet', async ({ page }) => {
    await signInViaApi(page, 'north.operator');

    await page.goto('/problems?status=all');

    await expect(page.getByRole('heading', { name: 'Проблемы', level: 2 })).toBeVisible();
    await expect(page.getByRole('table')).toBeHidden();
    const icons = page.getByTestId('nav-icons');
    await expect(icons.getByRole('link', { name: 'Заявки' })).toBeVisible();
    await expect(icons.getByRole('link', { name: /^Проблемы/ })).toHaveAttribute('aria-current', 'page');
    await expectNoHorizontalScroll(page);
    await page.getByRole('button', { name: 'Фильтры · 1' }).click();
    await expect(page.getByRole('dialog', { name: 'Фильтры' })).toBeVisible();
  });
});
