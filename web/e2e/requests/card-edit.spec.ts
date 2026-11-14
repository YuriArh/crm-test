import { expect, test } from '@playwright/test';
import { signInViaApi } from '../auth/helpers';
import { createRequestViaApi } from './helpers';

test('a comment is saved, survives a reload and is seen by the operator', async ({ page }) => {
  const request = await createRequestViaApi(page, 'north.initiator');
  const comment = `Позвонить за час ${Date.now().toString(36)}`;

  await page.goto(`/requests/${request.id}`);
  const field = page.getByRole('textbox', { name: 'Комментарий' });
  await field.fill(comment);
  await page.getByRole('button', { name: 'Сохранить' }).click();

  await expect(page.getByText('Сохранено')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Сохранить' })).toHaveCount(0);

  await page.reload();
  await expect(field).toHaveValue(comment);

  await signInViaApi(page, 'north.operator');
  await page.goto(`/requests/${request.id}`);
  await expect(page.getByRole('textbox', { name: 'Комментарий' })).toHaveValue(comment);
});

test('an emptied comment is removed', async ({ page }) => {
  const request = await createRequestViaApi(page, 'north.initiator');
  await page.goto(`/requests/${request.id}`);
  const field = page.getByRole('textbox', { name: 'Комментарий' });

  await field.fill('Временно');
  await page.getByRole('button', { name: 'Сохранить' }).click();
  await expect(page.getByRole('button', { name: 'Сохранить' })).toHaveCount(0);

  await field.fill('');
  await page.getByRole('button', { name: 'Сохранить' }).click();
  await expect(page.getByRole('button', { name: 'Сохранить' })).toHaveCount(0);

  await page.reload();
  await expect(field).toHaveValue('');
  await expect(field).toHaveAttribute('placeholder', 'Нет комментария');
});
