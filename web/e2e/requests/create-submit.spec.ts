import { expect, test, type Page } from '@playwright/test';
import { signInViaApi } from '../auth/helpers';
import { uniqueCargo } from './helpers';
import { cardIdFromUrl, fillCreateForm, openConfirm, recordCreates } from './create-page';

type Card = {
  id: string;
  number: string;
  cargoDescription: string;
  weightKg: number;
  deliveryAddress: string;
  businessStatus: string;
  result: { rejectionReason: string | null; trackingNumber: string | null } | null;
};

async function getCard(page: Page, id: string): Promise<Card> {
  const response = await page.request.get(`/api/requests/${id}`);
  expect(response.status()).toBe(200);
  return (await response.json()) as Card;
}

test.describe('create request: sending', () => {
  test.beforeEach(async ({ page }) => {
    await signInViaApi(page, 'north.initiator');
    await page.goto('/requests/new');
    await expect(page.getByRole('heading', { name: 'Новая заявка' })).toBeVisible();
  });

  test('creates a request through «Проверьте данные» and opens its card; the carrier executes it', async ({ page }) => {
    const cargo = uniqueCargo('Паллеты');
    const creates = recordCreates(page);
    await fillCreateForm(page, { cargo: `  ${cargo} `, weight: '120.5', address: 'ул. Ленина, 1, склад 3' });

    const dialog = await openConfirm(page);
    await expect(dialog.getByText(cargo, { exact: true })).toBeVisible();
    await expect(dialog.getByText('120,5 кг')).toBeVisible();
    await expect(dialog.getByText('изменить её будет нельзя', { exact: false })).toBeVisible();
    await dialog.getByRole('button', { name: 'Создать' }).click();

    const id = await cardIdFromUrl(page);
    const card = await getCard(page, id);
    expect(card).toMatchObject({ cargoDescription: cargo, weightKg: 120.5, deliveryAddress: 'ул. Ленина, 1, склад 3' });
    await expect(page.getByText(`Заявка ${card.number} создана`)).toBeVisible();
    expect(await creates.created()).toHaveLength(1);
    await expect
      .poll(async () => (await getCard(page, id)).businessStatus, { timeout: 30_000 })
      .toBe('SUCCEEDED');
  });

  test('«Изменить» returns to the form without creating anything', async ({ page }) => {
    const creates = recordCreates(page);
    await fillCreateForm(page, { cargo: uniqueCargo('Коробки') });

    const dialog = await openConfirm(page);
    await dialog.getByRole('button', { name: 'Изменить' }).click();

    await expect(dialog).toBeHidden();
    await expect(page).toHaveURL(/\/requests\/new$/);
    expect(creates.answers).toHaveLength(0);
  });

  test('an address with «тест-отказ» ends in a rejection with a reason', async ({ page }) => {
    await fillCreateForm(page, { cargo: uniqueCargo('Отказ'), address: 'ул. Ленина, 1, тест-отказ' });

    await (await openConfirm(page)).getByRole('button', { name: 'Создать' }).click();

    const id = await cardIdFromUrl(page);
    await expect.poll(async () => (await getCard(page, id)).businessStatus, { timeout: 30_000 }).toBe('REJECTED');
    expect((await getCard(page, id)).result?.rejectionReason).toBeTruthy();
  });

  test('a double press of «Создать» creates one request', async ({ page }) => {
    const creates = recordCreates(page);
    await fillCreateForm(page, { cargo: uniqueCargo('Двойное нажатие') });

    await (await openConfirm(page)).getByRole('button', { name: 'Создать' }).dblclick();

    const id = await cardIdFromUrl(page);
    const created = await creates.created();
    expect(created).toHaveLength(1);
    expect(created[0]!.id).toBe(id);
  });

  test('a repeat after a lost answer replays the same request instead of creating another', async ({ page }) => {
    const creates = recordCreates(page);
    let firstId: string | undefined;
    // The first create reaches dispatch, but its answer never reaches the page.
    await page.route('**/api/requests', async (route) => {
      if (route.request().method() !== 'POST' || firstId) return route.fallback();
      const response = await route.fetch();
      firstId = ((await response.json()) as { id: string }).id;
      await route.abort('connectionreset');
    });
    await fillCreateForm(page, { cargo: uniqueCargo('Обрыв') });

    await (await openConfirm(page)).getByRole('button', { name: 'Создать' }).click();
    await expect(page.getByRole('alert').filter({ hasText: 'Не удалось создать заявку' })).toBeVisible();
    await expect(page).toHaveURL(/\/requests\/new$/);

    await (await openConfirm(page)).getByRole('button', { name: 'Создать' }).click();

    const id = await cardIdFromUrl(page);
    expect(id).toBe(firstId);
    expect(await creates.created()).toHaveLength(0);
    expect(creates.answers).toEqual([{ status: 201, replayed: true, id }]);
  });
});
