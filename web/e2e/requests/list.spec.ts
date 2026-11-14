import { expect, test, type Page } from '@playwright/test';
import { signInViaApi } from '../auth/helpers';
import { createRequestViaApi, uniqueCargo } from './helpers';

// The registry is shared and grows with every run (and with other tests running in parallel), so the
// tests find their own rows by a unique cargo and never count rows.

const rowOf = (page: Page, cargo: string) => page.getByRole('row').filter({ hasText: cargo });

test('an initiator sees their new requests on top, newest first, and not those of another initiator', async ({
  page,
}) => {
  const other = await createRequestViaApi(page, 'south.initiator', { cargoDescription: uniqueCargo('Чужой груз') });
  const older = await createRequestViaApi(page, 'north.initiator', { cargoDescription: uniqueCargo('Груз раньше') });
  const newer = await createRequestViaApi(page, 'north.initiator', { cargoDescription: uniqueCargo('Груз позже') });

  await page.goto('/');

  await expect(page).toHaveURL((url) => url.pathname === '/requests');
  await expect(rowOf(page, newer.cargoDescription)).toBeVisible();
  await expect(rowOf(page, newer.cargoDescription)).toContainText(newer.number);
  await expect(rowOf(page, older.cargoDescription)).toBeVisible();
  const numbers = await page.getByRole('row').locator('td:first-child').allTextContents();
  expect(numbers.indexOf(newer.number)).toBeLessThan(numbers.indexOf(older.number));
  await expect(page.getByText(other.cargoDescription)).toHaveCount(0);
  await expect(page.getByRole('columnheader', { name: 'Автор' })).toHaveCount(0);
});

test('an operator sees the requests of their service with the author, and not those of another service', async ({
  page,
}) => {
  const own = await createRequestViaApi(page, 'north.initiator', { cargoDescription: uniqueCargo('Груз Севера') });
  const foreign = await createRequestViaApi(page, 'south.initiator', { cargoDescription: uniqueCargo('Груз Юга') });
  await signInViaApi(page, 'north.operator');

  await page.goto('/requests');

  const row = rowOf(page, own.cargoDescription);
  await expect(row).toBeVisible();
  await expect(row).toContainText('Инициатор (Север)');
  await expect(page.getByRole('columnheader', { name: 'Автор' })).toBeVisible();
  await expect(page.getByText(foreign.cargoDescription)).toHaveCount(0);
});

test('a row leads to the card of its request', async ({ page }) => {
  const created = await createRequestViaApi(page, 'north.initiator', { cargoDescription: uniqueCargo('Груз ссылка') });

  await page.goto('/requests');
  await rowOf(page, created.cargoDescription).getByRole('link', { name: created.number }).click();

  await expect(page).toHaveURL((url) => url.pathname === `/requests/${created.id}`);
});

test('the list pages through the address', async ({ page }) => {
  await createRequestViaApi(page, 'north.initiator');
  await signInViaApi(page, 'north.operator');
  await page.goto('/requests');
  await expect(page.getByText(/^1–\d+ из \d+$/)).toBeVisible();

  const second = page.getByRole('button', { name: 'Страница 2' });
  test.skip((await second.count()) === 0, 'fewer than 21 requests in the service');
  await second.click();

  await expect(page).toHaveURL((url) => url.searchParams.get('page') === '2');
  await expect(page.getByText(/^21–\d+ из \d+$/)).toBeVisible();
});
