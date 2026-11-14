import { expect, test } from '@playwright/test';
import { signInViaApi } from '../auth/helpers';
import { createRequestViaApi, uniqueCargo } from './helpers';

// The list refreshes itself every 10 s while the tab is visible: a request created elsewhere appears and
// then reaches its result while the page stays open (the carrier answers within a few seconds).

test('the list updates by itself: a new request appears and reaches its result without a reload', async ({ page }) => {
  test.setTimeout(60_000);
  await signInViaApi(page, 'north.initiator');
  await page.goto('/requests');
  await expect(page.getByRole('heading', { name: 'Заявки' })).toBeVisible();
  // Survives only as long as the page is not reloaded.
  await page.evaluate(() => Object.assign(window, { listMarker: true }));

  const created = await createRequestViaApi(page, 'north.initiator', { cargoDescription: uniqueCargo('Груз обновление') });

  const row = page.getByRole('row').filter({ hasText: created.cargoDescription });
  await expect(row).toBeVisible({ timeout: 15_000 });
  await expect(row.getByText('Выполнена')).toBeVisible({ timeout: 30_000 });
  await expect(row.getByText('Получена перевозчиком')).toBeVisible();
  expect(await page.evaluate(() => (window as unknown as { listMarker?: boolean }).listMarker)).toBe(true);
});
