import { expect, test, type Page } from '@playwright/test';
import { createRequestViaApi, uniqueCargo, type CreatedRequest } from './helpers';

const rowOf = (page: Page, cargo: string) => page.getByRole('row').filter({ hasText: cargo });

/** Waits until the carrier's result has reached dispatch (a second or a few). */
async function waitForResult(page: Page, request: CreatedRequest, status: 'SUCCEEDED' | 'REJECTED') {
  await expect
    .poll(
      async () => {
        const response = await page.request.get(`/api/requests/${request.id}`);
        return ((await response.json()) as { businessStatus: string }).businessStatus;
      },
      { timeout: 30_000 },
    )
    .toBe(status);
}

test('the result filter shows rejected requests only, from the address too and after a reload', async ({ page }) => {
  const succeeded = await createRequestViaApi(page, 'north.initiator', { cargoDescription: uniqueCargo('Груз успех') });
  const rejected = await createRequestViaApi(page, 'north.initiator', {
    cargoDescription: uniqueCargo('Груз отказ'),
    deliveryAddress: 'ул. Ленина, 1, тест-отказ',
  });
  await waitForResult(page, succeeded, 'SUCCEEDED');
  await waitForResult(page, rejected, 'REJECTED');

  await page.goto('/requests');
  await expect(rowOf(page, succeeded.cargoDescription)).toBeVisible();

  await page.getByRole('combobox', { name: 'Результат' }).click();
  await page.getByRole('option', { name: 'Отказ' }).click();

  await expect(page).toHaveURL((url) => url.searchParams.get('result') === 'REJECTED');
  await expect(rowOf(page, rejected.cargoDescription)).toContainText('Отказ');
  await expect(rowOf(page, succeeded.cargoDescription)).toHaveCount(0);

  await page.reload();
  await expect(rowOf(page, rejected.cargoDescription)).toBeVisible();
  await expect(rowOf(page, succeeded.cargoDescription)).toHaveCount(0);
  await expect(page.getByRole('combobox', { name: 'Результат' })).toHaveValue('Отказ');

  await page.goBack();
  await expect(page).toHaveURL((url) => url.pathname === '/requests' && url.search === '');
  await expect(rowOf(page, succeeded.cargoDescription)).toBeVisible();
});

test('a link with filters opens the filtered list directly', async ({ page }) => {
  const created = await createRequestViaApi(page, 'north.initiator', { cargoDescription: uniqueCargo('Груз ссылка') });
  await waitForResult(page, created, 'SUCCEEDED');

  await page.goto('/requests?result=SUCCEEDED&sort=old');
  await expect(page.getByRole('combobox', { name: 'Результат' })).toHaveValue('Выполнена');
  await expect(page.getByRole('combobox', { name: 'Сортировка' })).toHaveValue('Сначала старые');

  await page.getByRole('button', { name: 'Сбросить' }).click();
  await expect(page).toHaveURL((url) => url.search === '?sort=old');
});

test('only overdue requests: a fresh request with a future deadline is not among them', async ({ page }) => {
  const created = await createRequestViaApi(page, 'north.initiator', { cargoDescription: uniqueCargo('Груз срок') });

  await page.goto('/requests');
  await expect(rowOf(page, created.cargoDescription)).toBeVisible();
  await page.getByRole('switch', { name: 'Только просроченные' }).click();

  await expect(page).toHaveURL((url) => url.searchParams.get('overdue') === '1');
  await expect(rowOf(page, created.cargoDescription)).toHaveCount(0);
});
