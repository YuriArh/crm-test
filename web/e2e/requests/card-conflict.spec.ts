import { expect, test } from '@playwright/test';
import { signInViaApi } from '../auth/helpers';
import { createRequestViaApi } from './helpers';

test('two users edit the comment at once: the second one chooses, nothing is lost', async ({ browser }) => {
  const initiatorContext = await browser.newContext();
  const operatorContext = await browser.newContext();
  try {
    const initiator = await initiatorContext.newPage();
    const operator = await operatorContext.newPage();
    const request = await createRequestViaApi(initiator, 'north.initiator');
    await signInViaApi(operator, 'north.operator');
    const suffix = Date.now().toString(36);
    const initiatorText = `Текст инициатора ${suffix}`;
    const operatorText = `Текст оператора ${suffix}`;

    await initiator.goto(`/requests/${request.id}`);
    await operator.goto(`/requests/${request.id}`);

    // The initiator starts typing; the operator saves first.
    await initiator.getByRole('textbox', { name: 'Комментарий' }).fill(initiatorText);
    await operator.getByRole('textbox', { name: 'Комментарий' }).fill(operatorText);
    await operator.getByRole('button', { name: 'Сохранить' }).click();
    await expect(operator.getByText('Сохранено')).toBeVisible();

    // The initiator sees the operator's text either from the refresh or from the 409 of his save.
    const note = initiator.getByText('Изменил другой пользователь:');
    const save = initiator.getByRole('button', { name: 'Сохранить' });
    await expect(async () => {
      if (await save.isEnabled()) await save.click();
      await expect(note).toBeVisible({ timeout: 1_000 });
    }).toPass({ timeout: 15_000 });
    await expect(initiator.getByText(operatorText)).toBeVisible();
    await expect(initiator.getByRole('textbox', { name: 'Комментарий' })).toHaveValue(initiatorText);

    await initiator.getByRole('button', { name: 'Оставить моё' }).click();
    await expect(initiator.getByText('Сохранено')).toBeVisible();

    await operator.reload();
    await expect(operator.getByRole('textbox', { name: 'Комментарий' })).toHaveValue(initiatorText);
  } finally {
    await initiatorContext.close();
    await operatorContext.close();
  }
});

test('leaving the card with an unsaved comment asks first', async ({ page }) => {
  const request = await createRequestViaApi(page, 'north.initiator');
  await page.goto(`/requests/${request.id}`);

  await page.getByRole('textbox', { name: 'Комментарий' }).fill('Не сохранено');
  await page.getByRole('link', { name: 'Заявки' }).click();

  const dialog = page.getByRole('dialog', { name: 'Уйти без сохранения?' });
  await expect(dialog).toBeVisible();
  await dialog.getByRole('button', { name: 'Остаться' }).click();
  await expect(dialog).toBeHidden();
  await expect(page).toHaveURL(new RegExp(`/requests/${request.id}$`));
  await expect(page.getByRole('textbox', { name: 'Комментарий' })).toHaveValue('Не сохранено');

  await page.getByRole('link', { name: 'Заявки' }).click();
  await page.getByRole('dialog', { name: 'Уйти без сохранения?' }).getByRole('button', { name: 'Уйти' }).click();
  // Opened by a direct link: no in-app history, so the breadcrumb goes to the list.
  await expect(page).toHaveURL((url) => url.pathname === '/requests');
});
