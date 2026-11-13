import { expect, test } from '@playwright/test';

import { createRequest, login, logout } from './helpers';

test('initiator creates a request and sees the carrier result appear by itself', async ({ page }) => {
  await login(page, 'north.initiator');
  const cargo = `E2E паллеты ${Date.now()}`;
  const { number } = await createRequest(page, cargo);

  await expect(page.getByRole('region', { name: 'Груз и доставка' })).toContainText(cargo);
  // The card polls: the result arrives without a reload (receipt first, then the business result).
  await expect(page.getByLabel(/^Результат: (Выполнена|Отказ)$/)).toBeVisible({ timeout: 45_000 });
  await expect(page.getByLabel('Доставка: Получена перевозчиком')).toBeVisible();
  // Initiators do not see the technical exchange block.
  await expect(page.getByRole('region', { name: 'Обмен с перевозчиком' })).toHaveCount(0);

  await page.getByRole('link', { name: '← Все заявки' }).click();
  await expect(page.getByRole('link', { name: number })).toBeVisible();
});

test("an initiator cannot open another initiator's request, even by its URL", async ({ page }) => {
  await login(page, 'north.initiator');
  const { url } = await createRequest(page, `E2E чужая ${Date.now()}`);
  await logout(page);

  await login(page, 'south.initiator');
  await page.goto(url);
  await expect(page.getByText('Заявка не найдена')).toBeVisible();
});

test('operator assigns themselves and leaves a comment', async ({ page }) => {
  await login(page, 'north.initiator');
  const { url } = await createRequest(page, `E2E назначение ${Date.now()}`);
  await logout(page);

  await login(page, 'north.operator');
  await page.goto(url);
  await expect(page.getByRole('region', { name: 'Обмен с перевозчиком' })).toBeVisible();

  await page.getByRole('textbox', { name: 'Исполнитель' }).click();
  await page.getByRole('option', { name: 'Оператор (Север)' }).click();
  await page.getByLabel('Комментарий').fill('Проверено оператором');
  await page.getByRole('button', { name: 'Сохранить' }).click();
  await expect(page.getByText('Изменения сохранены')).toBeVisible();

  await page.reload();
  await expect(page.getByRole('textbox', { name: 'Исполнитель' })).toHaveValue('Оператор (Север)');
  await expect(page.getByLabel('Комментарий')).toHaveValue('Проверено оператором');
});

test('registry works at 390 px without horizontal scrolling', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await login(page, 'north.initiator');
  await expect(page.getByRole('link', { name: /DR-\d+/ }).first()).toBeVisible();
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  expect(overflow).toBeLessThanOrEqual(0);
});
