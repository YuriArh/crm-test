import { expect, test } from '@playwright/test';
import { signInViaApi } from '../auth/helpers';
import { createRequestViaApi, uniqueCargo } from '../requests/helpers';
import { allFaultsOff, requireCarrier, setFaults } from './carrier';

// Problems from real emulator faults (web-problems.md §10.4). The switches are global, so this file runs in the
// `faults` project: after every other test, one test at a time, every switch off before and after each test.
test.describe.configure({ mode: 'serial' });

// Like the Java e2e: the lost answer times out (10 s), the resend is answered, the card asks every 5 s.
const SYSTEM_TIMEOUT = 90_000;

test.beforeEach(async () => {
  await requireCarrier();
  await allFaultsOff();
});

test.afterEach(async () => {
  await allFaultsOff();
});

test('a broken carrier record is acknowledged with a comment in the card', async ({ page }) => {
  test.setTimeout(SYSTEM_TIMEOUT + 30_000);
  // malformed spoils the price of every SUCCEEDED result in the feed: the record becomes INVALID_MESSAGE.
  await setFaults({ malformed: true });
  const request = await createRequestViaApi(page, 'north.initiator', { cargoDescription: uniqueCargo('Проблемы цена') });
  await signInViaApi(page, 'north.operator');

  await page.goto(`/requests/${request.id}`);
  const problems = page.getByRole('region', { name: 'Проблемы' });
  const notice = problems.getByRole('alert').filter({ hasText: 'Некорректное сообщение перевозчика' });
  await expect(notice).toBeVisible({ timeout: SYSTEM_TIMEOUT });
  await setFaults({});
  await expect(notice.getByRole('region', { name: 'Сообщение перевозчика' })).toContainText('price');

  await notice.getByRole('button', { name: 'Разобрано' }).click();
  const dialog = page.getByRole('dialog', { name: 'Разобрано' });
  await dialog.getByRole('textbox', { name: 'Комментарий' }).fill('Сообщил перевозчику о битой цене');
  await dialog.getByRole('button', { name: 'Сохранить решение' }).click();

  await expect(page.getByText('Решение сохранено')).toBeVisible();
  await expect(notice).toHaveCount(0);
  await problems.getByRole('button', { name: /Закрытые проблемы/ }).click();
  const line = problems.getByRole('button', { name: /Некорректное сообщение перевозчика.*Оператор \(Север\).*Разобрано/ });
  await expect(line).toBeVisible();
  await line.click();
  await expect(problems.getByText('Сообщил перевозчику о битой цене')).toBeVisible();
});

test('a broken carrier record is found in the problems list and acknowledged in its panel', async ({ page }) => {
  test.setTimeout(SYSTEM_TIMEOUT + 30_000);
  await setFaults({ malformed: true });
  const request = await createRequestViaApi(page, 'north.initiator', { cargoDescription: uniqueCargo('Проблемы список') });
  await signInViaApi(page, 'north.operator');

  await page.goto('/problems?kind=%5B%22INVALID_MESSAGE%22%5D');
  const row = page.getByRole('row').filter({ hasText: request.number });
  await expect(row).toBeVisible({ timeout: SYSTEM_TIMEOUT });
  await setFaults({});

  await row.getByRole('link', { name: 'Некорректное сообщение перевозчика' }).click();
  const panel = page.getByRole('dialog', { name: 'Некорректное сообщение перевозчика' });
  await expect(panel.getByRole('region', { name: 'Сообщение перевозчика' })).toContainText('price');
  await panel.getByRole('button', { name: 'Разобрано' }).click();
  const dialog = page.getByRole('dialog', { name: 'Разобрано' });
  await dialog.getByRole('textbox', { name: 'Комментарий' }).fill('Цена испорчена, итог подтянет сверка');
  await dialog.getByRole('button', { name: 'Сохранить решение' }).click();

  await expect(panel.getByText(/Оператор \(Север\) · Разобрано/)).toBeVisible();
  await expect(panel.getByRole('button', { name: 'Разобрано' })).toHaveCount(0);
  await expect(page.getByRole('row').filter({ hasText: request.number })).toHaveCount(0);
  await panel.getByRole('button', { name: 'Закрыть' }).click();
  await expect(page).toHaveURL(/\/problems\?kind=/);

  // The segmented control's radio inputs are hidden; a person clicks the caption.
  await page.getByRole('radiogroup', { name: 'Статус' }).getByText('Закрытые', { exact: true }).click();
  await expect(page.getByRole('row').filter({ hasText: request.number })).toContainText('Разобрано · Оператор (Север)');
});

test('a lost answer shows «unknown whether received» in the card until the system closes it', async ({ page }) => {
  test.setTimeout(SYSTEM_TIMEOUT + 30_000);
  // A lost answer alone is not enough: the result comes by the feed before the send times out.
  await setFaults({ loseResponse: true, delayed: true });
  const request = await createRequestViaApi(page, 'north.initiator', { cargoDescription: uniqueCargo('Проблемы потеря') });
  await signInViaApi(page, 'north.operator');

  await page.goto(`/requests/${request.id}`);
  const problems = page.getByRole('region', { name: 'Проблемы' });
  const notice = problems.getByRole('alert').filter({ hasText: 'Неизвестно, получена ли заявка' });
  await expect(notice).toBeVisible({ timeout: SYSTEM_TIMEOUT });
  await setFaults({});

  await expect(notice).toHaveCount(0, { timeout: SYSTEM_TIMEOUT });
  await problems.getByRole('button', { name: /Закрытые проблемы/ }).click();
  await expect(problems.getByRole('button', { name: /Неизвестно, получена ли заявка.*Закрыта системой/ })).toBeVisible();
});
