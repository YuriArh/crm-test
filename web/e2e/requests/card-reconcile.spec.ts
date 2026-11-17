import { expect, test } from '@playwright/test';
import { signInViaApi } from '../auth/helpers';
import { createRequestViaApi, uniqueCargo } from './helpers';

// The carrier emulator answers within seconds; the card asks again every 5 s.
const RESULT_TIMEOUT = 30_000;

test('an operator reconciles a finished request: «Совпадает», and the check heads the history', async ({ page }) => {
  const request = await createRequestViaApi(page, 'north.initiator', { cargoDescription: uniqueCargo('Сверка совпадает') });
  await signInViaApi(page, 'north.operator');
  await page.goto(`/requests/${request.id}`);
  await expect(page.getByRole('region', { name: 'Результат' }).getByText('Номер отправления')).toBeVisible({
    timeout: RESULT_TIMEOUT,
  });

  const exchange = page.getByRole('region', { name: 'Обмен с перевозчиком' });
  const button = exchange.getByRole('button', { name: 'Сверить сейчас' });
  await button.click();

  await expect(exchange.getByRole('status')).toContainText('Совпадает');
  await expect(button).toBeFocused();
  const latest = page.getByRole('region', { name: 'Сверки' }).getByRole('button').first();
  await expect(latest).toContainText('вручную · Оператор (Север)');
  await expect(latest).toContainText('Совпадает');

  await latest.press('Enter');
  await expect(page.getByRole('region', { name: 'Сверки' }).getByText('Исполнена', { exact: true })).toBeVisible();
});

test('an initiator has neither «Сверить сейчас» nor the history', async ({ page }) => {
  const request = await createRequestViaApi(page, 'north.initiator', { cargoDescription: uniqueCargo('Сверка инициатор') });

  await page.goto(`/requests/${request.id}`);
  await expect(page.getByRole('heading', { name: request.number, level: 1 })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Сверить сейчас' })).toHaveCount(0);
  await expect(page.getByRole('region', { name: 'Сверки' })).toHaveCount(0);
});

test('the history fits a phone without sideways scrolling', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const request = await createRequestViaApi(page, 'north.initiator', { cargoDescription: uniqueCargo('Сверка телефон') });
  await signInViaApi(page, 'north.operator');
  await page.goto(`/requests/${request.id}`);
  await expect(page.getByRole('region', { name: 'Результат' }).getByText('Номер отправления')).toBeVisible({
    timeout: RESULT_TIMEOUT,
  });

  await page.getByRole('button', { name: 'Сверить сейчас' }).click();
  const latest = page.getByRole('region', { name: 'Сверки' }).getByRole('button').first();
  await expect(latest).toContainText('Совпадает');
  await latest.click();

  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow).toBe(0);
});
