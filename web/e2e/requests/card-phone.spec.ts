import { expect, test, type Page } from '@playwright/test';
import { signInViaApi } from '../auth/helpers';
import { createRequestViaApi } from './helpers';

test.use({ viewport: { width: 390, height: 844 } });

async function hasNoHorizontalScroll(page: Page): Promise<boolean> {
  return page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth);
}

test('on a phone the card fits the width and the save bar stays at the bottom', async ({ page }) => {
  const request = await createRequestViaApi(page, 'north.initiator');
  await signInViaApi(page, 'north.operator');

  await page.goto(`/requests/${request.id}`);
  await expect(page.getByRole('region', { name: 'Обмен с перевозчиком' })).toBeVisible();
  expect(await hasNoHorizontalScroll(page)).toBe(true);

  await page.getByRole('textbox', { name: 'Комментарий' }).fill('С телефона');
  const bar = page.getByRole('region', { name: 'Несохранённые изменения' });
  await expect(bar).toBeVisible();

  // Scrolled to the top, the bar is still on screen, at its bottom edge.
  await page.evaluate(() => window.scrollTo(0, 0));
  await expect(bar).toBeInViewport();
  const box = await bar.boundingBox();
  expect(box).not.toBeNull();
  expect(box!.y + box!.height).toBeGreaterThan(844 * 0.8);
  expect(await hasNoHorizontalScroll(page)).toBe(true);

  await bar.getByRole('button', { name: 'Сохранить' }).click();
  await expect(page.getByText('Сохранено')).toBeVisible();
  await expect(bar).toHaveCount(0);
});

test('on a phone the tracking number can be copied', async ({ page, context }) => {
  await context.grantPermissions(['clipboard-read', 'clipboard-write']);
  const request = await createRequestViaApi(page, 'north.initiator');

  await page.goto(`/requests/${request.id}`);
  const result = page.getByRole('region', { name: 'Результат' });
  const copy = result.getByRole('button', { name: 'Скопировать номер отправления' });
  await expect(copy).toBeVisible({ timeout: 30_000 });
  expect(await hasNoHorizontalScroll(page)).toBe(true);

  await copy.click();

  await expect(result.getByRole('button', { name: 'Скопировано' })).toBeVisible();
  const copied = await page.evaluate(() => navigator.clipboard.readText());
  expect(copied).not.toBe('');
  await expect(result.getByText(copied, { exact: true })).toBeVisible();
});
