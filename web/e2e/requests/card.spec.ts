import { expect, test } from '@playwright/test';
import { signInViaApi } from '../auth/helpers';
import { createRequestViaApi, uniqueCargo } from './helpers';

// The carrier emulator answers within seconds; the card asks again every 5 s.
const RESULT_TIMEOUT = 30_000;

test('a new request reaches its result on the open card without a reload', async ({ page }) => {
  const cargo = uniqueCargo('Карточка успех');
  const request = await createRequestViaApi(page, 'north.initiator', { cargoDescription: cargo });

  await page.goto(`/requests/${request.id}`);

  await expect(page.getByRole('heading', { name: request.number, level: 1 })).toBeVisible();
  const content = page.getByRole('region', { name: 'Содержимое' });
  await expect(content.getByText(cargo)).toBeVisible();
  await expect(content.getByText('Содержимое заявки не меняется после создания')).toBeVisible();

  const result = page.getByRole('region', { name: 'Результат' });
  await expect(result.getByText('Номер отправления')).toBeVisible({ timeout: RESULT_TIMEOUT });
  await expect(result.getByText('₽')).toBeVisible();
  await expect(page.getByText('Выполнена', { exact: true })).toBeVisible();
  await expect(page.getByText('Получена перевозчиком')).toBeVisible();
});

test('a rejected request shows the reason', async ({ page }) => {
  const request = await createRequestViaApi(page, 'north.initiator', {
    cargoDescription: uniqueCargo('Карточка отказ'),
    deliveryAddress: 'ул. Тестовая, тест-отказ',
  });

  await page.goto(`/requests/${request.id}`);

  const result = page.getByRole('region', { name: 'Результат' });
  await expect(result.getByText('Причина отказа')).toBeVisible({ timeout: RESULT_TIMEOUT });
  await expect(page.getByText('Отказ', { exact: true })).toBeVisible();
});

test('an initiator does not see the exchange block, an operator of the service does', async ({ page }) => {
  const request = await createRequestViaApi(page, 'north.initiator');

  await page.goto(`/requests/${request.id}`);
  await expect(page.getByRole('heading', { name: request.number, level: 1 })).toBeVisible();
  await expect(page.getByRole('region', { name: 'Обмен с перевозчиком' })).toHaveCount(0);

  await signInViaApi(page, 'north.operator');
  await page.goto(`/requests/${request.id}`);
  const exchange = page.getByRole('region', { name: 'Обмен с перевозчиком' });
  await expect(exchange).toBeVisible();
  await expect(exchange.getByText('Идентификатор операции')).toBeVisible();
});

test("an initiator does not open someone else's request by a direct link", async ({ page }) => {
  const request = await createRequestViaApi(page, 'south.initiator');

  await signInViaApi(page, 'north.initiator');
  await page.goto(`/requests/${request.id}`);

  await expect(page.getByRole('heading', { name: 'Заявка не найдена' })).toBeVisible();
  await expect(page.getByText(request.number)).toHaveCount(0);
});

test('a malformed id is "not found" too', async ({ page }) => {
  await signInViaApi(page, 'north.initiator');

  await page.goto('/requests/not-a-request');

  await expect(page.getByRole('heading', { name: 'Заявка не найдена' })).toBeVisible();
});
