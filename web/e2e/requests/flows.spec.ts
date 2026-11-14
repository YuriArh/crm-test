import { expect, test, type Page } from '@playwright/test';
import { signInViaApi } from '../auth/helpers';
import { fillCreateForm, openConfirm } from './create-page';
import { createRequestViaApi, uniqueCargo } from './helpers';

// Journeys across the three screens (list, card, create), which were built on separate branches.

const rowOf = (page: Page, cargo: string) => page.getByRole('row').filter({ hasText: cargo });

async function waitForStatus(page: Page, id: string, status: string) {
  await expect
    .poll(async () => ((await (await page.request.get(`/api/requests/${id}`)).json()) as { businessStatus: string }).businessStatus, {
      timeout: 20_000,
    })
    .toBe(status);
}

test('an initiator creates a request from the list, sees its result on the card and goes back to the list', async ({ page }) => {
  const cargo = uniqueCargo('Сквозной');
  await signInViaApi(page, 'north.initiator');
  await page.goto('/requests');

  await page.getByRole('link', { name: 'Новая заявка' }).first().click();
  await expect(page).toHaveURL((url) => url.pathname === '/requests/new');
  await fillCreateForm(page, { cargo });
  const dialog = await openConfirm(page);
  await dialog.getByRole('button', { name: 'Создать' }).click();

  await expect(page).toHaveURL((url) => /^\/requests\/[0-9a-f-]{36}$/.test(url.pathname));
  await expect(page.getByText(cargo)).toBeVisible();
  await expect(page.getByText('Выполнена').first()).toBeVisible({ timeout: 20_000 });

  // The form was replaced in the history: «Заявки» leads to the list, not back to the form.
  await page.getByRole('link', { name: 'Заявки' }).click();
  await expect(page).toHaveURL((url) => url.pathname === '/requests');
  await expect(rowOf(page, cargo)).toContainText('Выполнена');
});

test('the list keeps its filter on the way to a card and back', async ({ page }) => {
  const created = await createRequestViaApi(page, 'north.initiator', {
    cargoDescription: uniqueCargo('Фильтр'),
    deliveryAddress: 'ул. Ленина, 1, тест-отказ',
  });
  await waitForStatus(page, created.id, 'REJECTED');

  await page.goto('/requests?result=REJECTED');
  await rowOf(page, created.cargoDescription).getByRole('link', { name: created.number }).click();
  await expect(page).toHaveURL((url) => url.pathname === `/requests/${created.id}`);
  await expect(page.getByText('Отказ').first()).toBeVisible();

  await page.getByRole('link', { name: 'Заявки' }).click();
  await expect(page).toHaveURL((url) => url.pathname === '/requests' && url.searchParams.get('result') === 'REJECTED');
  await expect(rowOf(page, created.cargoDescription)).toBeVisible();
});

test('an assignee set on the card shows in the list for the operator and the initiator', async ({ page }) => {
  const created = await createRequestViaApi(page, 'north.initiator', { cargoDescription: uniqueCargo('Ответственный') });

  await signInViaApi(page, 'north.operator');
  await page.goto(`/requests/${created.id}`);
  await page.getByRole('combobox', { name: 'Ответственный' }).click();
  await page.getByRole('option', { name: 'Оператор (Север)' }).click();
  await page.getByRole('button', { name: 'Сохранить' }).click();
  await expect(page.getByRole('button', { name: 'Сохранить' })).toHaveCount(0);

  await page.goto('/requests');
  await expect(rowOf(page, created.cargoDescription)).toContainText('Оператор (Север)');

  await signInViaApi(page, 'north.initiator');
  await page.goto('/requests');
  await expect(rowOf(page, created.cargoDescription)).toContainText('Оператор (Север)');
});
