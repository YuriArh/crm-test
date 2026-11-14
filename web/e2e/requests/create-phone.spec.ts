import { expect, test, type Page } from '@playwright/test';
import { signInViaApi } from '../auth/helpers';
import { uniqueCargo } from './helpers';
import { cardIdFromUrl, dayLabel, openConfirm } from './create-page';

async function hasNoHorizontalScroll(page: Page): Promise<boolean> {
  return page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth);
}

test.describe('create request on a phone', () => {
  test.use({ viewport: { width: 390, height: 844 } });

  test('the form and «Проверьте данные» fit the screen and the request reaches its card', async ({ page }) => {
    await signInViaApi(page, 'north.initiator');
    await page.goto('/requests/new');
    await expect(page.getByRole('heading', { name: 'Новая заявка' })).toBeVisible();
    expect(await hasNoHorizontalScroll(page)).toBe(true);
    await expect(page.getByRole('textbox', { name: 'Вес, кг' })).toHaveAttribute('inputmode', 'decimal');

    const cargo = uniqueCargo('Телефон');
    await page.getByRole('textbox', { name: 'Груз', exact: true }).fill(cargo);
    await page.getByRole('textbox', { name: 'Вес, кг' }).fill('3,5');
    await page.getByRole('textbox', { name: 'Адрес доставки', exact: true }).fill(`ул. Длинная-${'очень'.repeat(30)}, 1`);

    // The calendar opens in a modal window on phones.
    await page.getByRole('button', { name: 'Срок доставки' }).click();
    const calendar = page.getByRole('dialog').filter({ has: page.getByRole('button', { name: dayLabel(0), exact: true }) });
    await expect(calendar).toBeVisible();
    await expect(calendar).toHaveAttribute('aria-modal', 'true');
    await calendar.getByRole('button', { name: dayLabel(0), exact: true }).click();
    await expect(calendar).toBeHidden();

    const dialog = await openConfirm(page);
    await expect(dialog.getByText(cargo, { exact: true })).toBeVisible();
    expect(await hasNoHorizontalScroll(page)).toBe(true);
    await dialog.getByRole('button', { name: 'Создать' }).click();

    const id = await cardIdFromUrl(page);
    const response = await page.request.get(`/api/requests/${id}`);
    expect(response.status()).toBe(200);
    expect(((await response.json()) as { cargoDescription: string }).cargoDescription).toBe(cargo);
  });
});
