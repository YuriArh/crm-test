import { expect, test } from '@playwright/test';
import { signInViaApi } from '../auth/helpers';
import { createRequestViaApi } from './helpers';

/** The emulator executes a request within seconds; the card refreshes every 5 s. */
const RESULT_TIMEOUT = 30_000;

test('the operator sees the whole history', async ({ page }) => {
  const request = await createRequestViaApi(page, 'north.initiator');
  await signInViaApi(page, 'north.operator');
  await page.goto(`/requests/${request.id}`);

  const history = page.getByRole('region', { name: 'История' });
  await expect(history.getByRole('heading', { name: 'Итог: Выполнена' })).toBeVisible({ timeout: RESULT_TIMEOUT });
  // The attempt that got through; on a freshly started stack an earlier one may meet the carrier still starting.
  const accepted = history.getByRole('heading', { name: /^Попытка отправки \d+: принята$/ });
  await expect(accepted).toBeVisible();
  await expect(history.getByRole('heading', { name: 'Заявка создана' })).toBeVisible();

  const comment = `Проверено ${Date.now().toString(36)}`;
  await page.getByRole('textbox', { name: 'Комментарий' }).fill(comment);
  await page.getByRole('button', { name: 'Сохранить' }).click();

  const edit = history.getByRole('listitem').filter({ has: page.getByRole('heading', { name: 'Заявка изменена' }) });
  await expect(edit).toBeVisible();
  await expect(edit).toContainText('Оператор (Север)');
  await expect(edit).toContainText(`Комментарий: — → ${comment}`);
  // The newest entry is on top.
  await expect(history.getByRole('listitem').first()).toContainText('Заявка изменена');

  await history.getByText('Обмен', { exact: true }).click();
  await expect(edit).toHaveCount(0);
  await expect(accepted).toBeVisible();
});

test('the initiator sees the business part', async ({ page }) => {
  const request = await createRequestViaApi(page, 'north.initiator');
  await page.goto(`/requests/${request.id}`);

  const history = page.getByRole('region', { name: 'История' });
  await expect(history.getByRole('heading', { name: 'Итог: Выполнена' })).toBeVisible({ timeout: RESULT_TIMEOUT });
  await expect(history.getByRole('heading', { name: 'Перевозчик получил заявку' })).toBeVisible();
  await expect(history.getByRole('heading', { name: 'Заявка создана' })).toBeVisible();
  await expect(history.getByRole('heading', { name: /Попытка отправки/ })).toHaveCount(0);
  await expect(history.getByRole('heading', { name: /Запись перевозчика/ })).toHaveCount(0);
  await expect(history.getByRole('radio')).toHaveCount(0);
});
