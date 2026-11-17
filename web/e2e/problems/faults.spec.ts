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
