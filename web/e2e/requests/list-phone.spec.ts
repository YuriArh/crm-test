import { expect, test, type Locator, type Page } from '@playwright/test';
import { signInViaApi } from '../auth/helpers';
import { createRequestViaApi, uniqueCargo } from './helpers';

test.use({ viewport: { width: 390, height: 844 } });

const cardOf = (page: Page, cargo: string) => page.getByRole('link').filter({ hasText: cargo });

async function expectTouchTarget(locator: Locator) {
  const box = await locator.boundingBox();
  expect(box, 'element is on screen').not.toBeNull();
  expect(box!.height).toBeGreaterThanOrEqual(40);
}

async function expectNoHorizontalScroll(page: Page) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow).toBeLessThanOrEqual(0);
}

test('on a phone the registry is a list of cards without horizontal scrolling', async ({ page }) => {
  const created = await createRequestViaApi(page, 'north.initiator', { cargoDescription: uniqueCargo('Груз телефон') });
  await signInViaApi(page, 'north.operator');

  await page.goto('/requests');

  const card = cardOf(page, created.cargoDescription);
  await expect(card).toBeVisible();
  await expect(card).toContainText(created.number);
  await expect(card).toContainText('Инициатор (Север)');
  await expect(page.getByRole('table')).toBeHidden();
  await expect(page.getByRole('banner').getByText('Оператор · Служба Север')).toBeHidden();
  await expectNoHorizontalScroll(page);

  await expectTouchTarget(page.getByRole('button', { name: 'Фильтры' }));
  await expectTouchTarget(page.getByRole('combobox', { name: 'Сортировка' }));
  await expectTouchTarget(card);

  await card.click();
  await expect(page).toHaveURL((url) => url.pathname === `/requests/${created.id}`);
});

test('on a phone the filters live in a bottom drawer, and chips remove them', async ({ page }) => {
  const created = await createRequestViaApi(page, 'north.initiator', { cargoDescription: uniqueCargo('Груз шторка') });

  await page.goto('/requests');
  await expect(cardOf(page, created.cargoDescription)).toBeVisible();

  await page.getByRole('button', { name: 'Фильтры' }).click();
  const drawer = page.getByRole('dialog', { name: 'Фильтры' });
  await expect(drawer).toBeVisible();
  await expect(drawer.getByRole('combobox', { name: 'Доставка' })).toHaveCount(0);
  await drawer.getByRole('switch', { name: 'Только просроченные' }).click();
  await drawer.getByRole('button', { name: 'Показать' }).click();

  await expect(drawer).toBeHidden();
  await expect(page).toHaveURL((url) => url.searchParams.get('overdue') === '1');
  await expect(cardOf(page, created.cargoDescription)).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Фильтры · 1' })).toBeVisible();
  await expectNoHorizontalScroll(page);

  const chip = page.getByRole('button', { name: 'Снять фильтр «Просроченные»' });
  await expectTouchTarget(chip);
  await chip.click();

  await expect(page).toHaveURL((url) => url.search === '');
  await expect(cardOf(page, created.cargoDescription)).toBeVisible();
});

test('on a phone the pager is compact', async ({ page }) => {
  await createRequestViaApi(page, 'north.initiator');
  await signInViaApi(page, 'north.operator');
  await page.goto('/requests');
  await expect(page.getByRole('link').first()).toBeVisible();

  const next = page.getByRole('button', { name: 'Следующая страница' });
  test.skip((await next.count()) === 0, 'fewer than 21 requests in the service');
  await expect(page.getByText(/^1 \/ \d+$/)).toBeVisible();
  await expectTouchTarget(next);
  await next.click();

  await expect(page).toHaveURL((url) => url.searchParams.get('page') === '2');
  await expect(page.getByText(/^2 \/ \d+$/)).toBeVisible();
  await expectNoHorizontalScroll(page);
});
