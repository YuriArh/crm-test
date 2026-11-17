import { expect, test } from '@playwright/test';
import { signInViaApi } from '../auth/helpers';
import { createRequestViaApi, uniqueCargo } from '../requests/helpers';
import { allFaultsOff, requireCarrier, setFaults, waitForDeferredResult, waitForReceipt } from './carrier';
import { changeCarrierResult } from './carrierDb';

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
  // The header counts it among the open problems.
  await expect(page.getByRole('navigation', { name: 'Разделы' }).getByRole('link', { name: /^Проблемы, открытых: \d+$/ }).first()).toBeVisible();
  // The registry shows it among the requests with open problems, with its count.
  await page.goto('/requests?problems=1');
  const registryRow = page.getByRole('row').filter({ hasText: request.number });
  await expect(registryRow.getByLabel('Открытых проблем: 1')).toBeVisible();
  await page.goto('/problems?kind=%5B%22INVALID_MESSAGE%22%5D');

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

  await page.goto('/requests?problems=1');
  await expect(page.getByRole('heading', { name: 'Заявки', level: 2 })).toBeVisible();
  await expect(page.getByRole('row').filter({ hasText: request.number })).toHaveCount(0);
});

test('«Сверить сейчас» takes over a result the carrier has not published yet', async ({ page }) => {
  test.setTimeout(2 * SYSTEM_TIMEOUT);
  // delayed: the carrier executes at once but holds the result back from its feed (15 s on the stand).
  // The mode is remembered per operation at its first receipt, so it is switched off once the carrier has it.
  await setFaults({ delayed: true });
  const request = await createRequestViaApi(page, 'north.initiator', { cargoDescription: uniqueCargo('Сверка задержка') });
  const card = (await (await page.request.get(`/api/requests/${request.id}`)).json()) as { operationId: string };
  await waitForReceipt(card.operationId);
  await setFaults({});
  await signInViaApi(page, 'north.operator');
  await page.goto(`/requests/${request.id}`);
  const exchange = page.getByRole('region', { name: 'Обмен с перевозчиком' });
  await expect(exchange).toBeVisible();
  await waitForDeferredResult(card.operationId);

  await exchange.getByRole('button', { name: 'Сверить сейчас' }).click();

  await expect(exchange.getByRole('status')).toContainText('Итог получен сверкой');
  const result = page.getByRole('region', { name: 'Результат' });
  await expect(result.getByText('Номер отправления')).toBeVisible();
  const tracking = await result.getByText(/^TRK-/).textContent();
  await expect(page.getByRole('region', { name: 'Сверки' }).getByRole('button').first()).toContainText('Итог получен сверкой');

  // The feed brings the same result later: it changes nothing.
  await page.waitForTimeout(25_000);
  await page.reload();
  await expect(result.getByText(tracking!)).toBeVisible();
  await expect(page.getByRole('region', { name: 'Проблемы' })).toHaveCount(0);
});

test('a resolved reconciliation conflict still shows what differed', async ({ page }) => {
  test.setTimeout(SYSTEM_TIMEOUT + 30_000);
  const request = await createRequestViaApi(page, 'north.initiator', { cargoDescription: uniqueCargo('Сверка расхождение') });
  await signInViaApi(page, 'north.operator');
  let operationId = '';
  await expect(async () => {
    const card = (await (await page.request.get(`/api/requests/${request.id}`)).json()) as {
      operationId: string;
      businessStatus: string;
    };
    operationId = card.operationId;
    expect(['SUCCEEDED', 'REJECTED']).toContain(card.businessStatus);
  }).toPass({ timeout: SYSTEM_TIMEOUT });
  // The carrier changed its result at home (docs/specs/demo-seed.md §8.1).
  changeCarrierResult(operationId, 'TRK-FFFFFFFF', '999.00');

  await page.goto(`/requests/${request.id}`);
  const exchange = page.getByRole('region', { name: 'Обмен с перевозчиком' });
  await exchange.getByRole('button', { name: 'Сверить сейчас' }).click();
  await expect(exchange.getByRole('status')).toContainText('Расхождение');

  const problems = page.getByRole('region', { name: 'Проблемы' });
  const notice = problems.getByRole('alert').filter({ hasText: 'Расхождение при сверке' });
  await notice.getByRole('button', { name: 'Принять версию перевозчика' }).click();
  const dialog = page.getByRole('dialog', { name: 'Принять версию перевозчика' });
  await dialog.getByRole('textbox', { name: 'Комментарий' }).fill('Перевозчик подтвердил новый номер');
  await dialog.getByRole('button', { name: 'Сохранить решение' }).click();
  await expect(page.getByText('Решение сохранено')).toBeVisible();
  await expect(page.getByRole('region', { name: 'Результат' }).getByText('TRK-FFFFFFFF')).toBeVisible();

  // The closed problem still shows both sides of the moment it was found, and the result now apart.
  await problems.getByRole('button', { name: /Закрытые проблемы/ }).click();
  await problems.getByRole('button', { name: /Расхождение при сверке.*Принята версия перевозчика/ }).click();
  const carrier = problems.getByRole('region', { name: 'Версия перевозчика', exact: true });
  await expect(carrier.getByText('TRK-FFFFFFFF', { exact: true })).toBeVisible();
  await expect(carrier.getByText('отличается').first()).toBeVisible();
  const ours = problems.getByRole('region', { name: 'Наш итог при обнаружении', exact: true });
  await expect(ours).toBeVisible();
  await expect(ours.getByText('TRK-FFFFFFFF', { exact: true })).toHaveCount(0);
  const now = problems.getByRole('region', { name: 'Итог сейчас', exact: true });
  await expect(now.getByText('TRK-FFFFFFFF', { exact: true })).toBeVisible();

  // So does the check in «Сверки»: its row names what differed.
  const check = page.getByRole('region', { name: 'Сверки' }).getByRole('button').first();
  await expect(check).toContainText('Расхождение');
  await expect(check).toContainText(/Отличается: .*номер отправления/);
});

test('a lost answer shows «unknown whether received» in the card until the system closes it', async ({ page }) => {
  test.setTimeout(2 * SYSTEM_TIMEOUT);
  // The answer to the first send is lost. Once the carrier has that send, it becomes unavailable: the resends get
  // 503 and the feed is closed, so «unknown whether received» stays open long enough to be seen (with the carrier
  // reachable the resend confirms receipt within ~5 s — less than the card's refresh interval).
  await setFaults({ loseResponse: true });
  const request = await createRequestViaApi(page, 'north.initiator', { cargoDescription: uniqueCargo('Проблемы потеря') });
  const card = (await (await page.request.get(`/api/requests/${request.id}`)).json()) as { operationId: string };
  await waitForReceipt(card.operationId);
  await setFaults({ loseResponse: true, unavailable: true });
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
