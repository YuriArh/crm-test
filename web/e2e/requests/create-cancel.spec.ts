import { expect, test } from '@playwright/test';
import { signInViaApi } from '../auth/helpers';

test.describe('create request: cancel', () => {
  test.beforeEach(async ({ page }) => {
    await signInViaApi(page, 'north.initiator');
    await page.goto('/requests/new');
    await expect(page.getByRole('heading', { name: 'Новая заявка' })).toBeVisible();
  });

  test('an empty form goes straight back to the registry', async ({ page }) => {
    await page.getByRole('link', { name: 'Отмена' }).click();

    await expect(page).toHaveURL(/\/requests$/);
  });

  test('a filled form asks first and keeps the data on «Продолжить заполнение»', async ({ page }) => {
    const cargo = page.getByRole('textbox', { name: 'Груз', exact: true });
    await cargo.fill('Паллеты');

    await page.getByRole('link', { name: 'Отмена' }).click();
    const dialog = page.getByRole('dialog', { name: 'Отменить создание?' });
    await expect(dialog.getByText('Введённые данные пропадут.')).toBeVisible();
    await dialog.getByRole('button', { name: 'Продолжить заполнение' }).click();

    await expect(dialog).toBeHidden();
    await expect(page).toHaveURL(/\/requests\/new$/);
    await expect(cargo).toHaveValue('Паллеты');

    await page.getByRole('link', { name: 'Отмена' }).click();
    await dialog.getByRole('button', { name: 'Отменить создание' }).click();

    await expect(page).toHaveURL(/\/requests$/);
  });
});
