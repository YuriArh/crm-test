import { expect, test, type Locator, type Page } from '@playwright/test';
import { DEMO_PASSWORD, signInViaApi } from '../auth/helpers';
import { createRequestViaApi, uniqueCargo } from '../requests/helpers';
import { cardIdFromUrl, dayLabel } from '../requests/create-page';

// NF06: the main actions are available from the keyboard. Every step below moves focus with Tab (or the keys a
// widget is meant to take) and acts with Enter, Space or typing; nothing is clicked.

/** Presses Tab until `target` has focus; fails with what got focus instead, so a skipped control is visible. */
async function tabTo(page: Page, target: Locator, maxPresses = 60): Promise<void> {
  await expect(target).toBeVisible();
  const seen: string[] = [];
  for (let i = 0; i < maxPresses; i += 1) {
    await page.keyboard.press('Tab');
    if (await target.evaluate((element) => element === document.activeElement)) return;
    seen.push(
      await page.evaluate(() => {
        const element = document.activeElement;
        if (!element) return '(none)';
        const name = element.getAttribute('aria-label') ?? element.textContent?.trim().slice(0, 30) ?? '';
        return `${element.tagName.toLowerCase()} «${name}»`;
      }),
    );
  }
  throw new Error(`Tab never reached the target; focus went through: ${seen.join(' → ')}`);
}

test.describe('keyboard only', () => {
  test('an initiator signs in, creates a request, opens it from the registry and comments on it', async ({ page }) => {
    const cargo = uniqueCargo('Клавиатура');
    const comment = `С клавиатуры ${Date.now().toString(36)}`;

    // Sign in.
    await page.goto('/login');
    await tabTo(page, page.getByRole('textbox', { name: 'Логин' }));
    await page.keyboard.type('north.initiator');
    await page.keyboard.press('Tab');
    await expect(page.getByLabel('Пароль')).toBeFocused();
    await page.keyboard.type(DEMO_PASSWORD);
    await page.keyboard.press('Enter');
    await expect(page.getByRole('heading', { name: 'Заявки', level: 2 })).toBeVisible();

    // Registry → create form.
    await tabTo(page, page.getByRole('link', { name: 'Новая заявка' }).first());
    await page.keyboard.press('Enter');
    await expect(page.getByRole('heading', { name: 'Новая заявка' })).toBeVisible();

    // Fill the form.
    await tabTo(page, page.getByRole('textbox', { name: 'Груз', exact: true }));
    await page.keyboard.type(cargo);
    await tabTo(page, page.getByRole('textbox', { name: 'Вес, кг' }));
    await page.keyboard.type('12,5');
    await tabTo(page, page.getByRole('textbox', { name: 'Адрес доставки', exact: true }));
    await page.keyboard.type('ул. Ленина, 1');
    await tabTo(page, page.getByRole('button', { name: 'Срок доставки' }));
    await page.keyboard.press('Enter');
    const today = page.getByRole('button', { name: dayLabel(0), exact: true });
    await expect(today).toBeVisible();
    await tabTo(page, today);
    await page.keyboard.press('Enter');
    await expect(today).toBeHidden();

    // Send through «Проверьте данные».
    await tabTo(page, page.getByRole('button', { name: 'Создать заявку' }));
    await page.keyboard.press('Enter');
    const dialog = page.getByRole('dialog', { name: 'Проверьте данные' });
    await expect(dialog).toBeVisible();
    await tabTo(page, dialog.getByRole('button', { name: 'Создать' }));
    await page.keyboard.press('Enter');
    const id = await cardIdFromUrl(page);

    // Back to the registry and into the card again by its row.
    await page.goto('/requests');
    const row = page.locator(`a[href="/requests/${id}"]`).first();
    await expect(row).toBeVisible();
    await tabTo(page, row);
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(new RegExp(`/requests/${id}$`));

    // Comment and save.
    await tabTo(page, page.getByRole('textbox', { name: 'Комментарий' }));
    await page.keyboard.type(comment);
    await tabTo(page, page.getByRole('button', { name: 'Сохранить' }));
    await page.keyboard.press('Enter');
    await expect(page.getByText('Сохранено')).toBeVisible();
    await page.reload();
    await expect(page.getByRole('textbox', { name: 'Комментарий' })).toHaveValue(comment);
  });

  test('an operator assigns a request to himself with the keyboard', async ({ page }) => {
    const request = await createRequestViaApi(page, 'north.initiator');
    await signInViaApi(page, 'north.operator');
    await page.goto(`/requests/${request.id}`);

    const assignee = page.getByRole('combobox', { name: 'Ответственный' });
    await expect(assignee).toHaveValue('Не назначен');
    await tabTo(page, assignee);
    await page.keyboard.press('ArrowDown');
    const option = page.getByRole('option', { name: 'Оператор (Север)' });
    await expect(option).toBeVisible();
    // Move the selection onto the operator, whatever the list's order, then take it.
    for (let i = 0; i < 5 && (await option.getAttribute('data-combobox-selected')) === null; i += 1) {
      await page.keyboard.press('ArrowDown');
    }
    await page.keyboard.press('Enter');
    await expect(assignee).toHaveValue('Оператор (Север)');

    await tabTo(page, page.getByRole('button', { name: 'Сохранить' }));
    await page.keyboard.press('Enter');
    await expect(page.getByText('Сохранено')).toBeVisible();
  });

  test('the registry filters are reachable and reset from the keyboard', async ({ page }) => {
    await signInViaApi(page, 'north.operator');
    await page.goto('/requests');
    await expect(page.getByRole('heading', { name: 'Заявки', level: 2 })).toBeVisible();

    const overdue = page.getByTestId('filter-bar').getByRole('switch', { name: 'Только просроченные' });
    await tabTo(page, overdue);
    await page.keyboard.press('Space');
    await expect(page).toHaveURL(/overdue=1/);
    await expect(overdue).toBeChecked();

    await tabTo(page, page.getByRole('button', { name: /Сбросить/ }).first());
    await page.keyboard.press('Enter');
    await expect(page).not.toHaveURL(/overdue=/);
    await expect(overdue).not.toBeChecked();
  });

  test('a sign-out from the header works from the keyboard', async ({ page }) => {
    await signInViaApi(page, 'north.initiator');
    await page.goto('/requests');
    await expect(page.getByRole('heading', { name: 'Заявки', level: 2 })).toBeVisible();

    await tabTo(page, page.getByRole('button', { name: 'Выйти' }));
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(/\/login/);
  });
});
