import { expect, test } from '@playwright/test';
import { signInViaApi } from '../auth/helpers';

/** Today's date in Moscow (the stand's business zone) as `D MMMM YYYY` in Russian, the calendar's day label. */
function dayLabel(offsetDays = 0): string {
  const [year, month, day] = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Europe/Moscow',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  })
    .format(new Date())
    .split('-')
    .map(Number);
  const date = new Date(Date.UTC(year!, month! - 1, day! + offsetDays));
  const monthName = new Intl.DateTimeFormat('ru-RU', { month: 'long', day: 'numeric', timeZone: 'UTC' })
    .format(date)
    .split(' ')[1];
  return `${date.getUTCDate()} ${monthName} ${date.getUTCFullYear()}`;
}

test.describe('create request form', () => {
  test('an initiator opens the form and the empty form is not sent', async ({ page }) => {
    await signInViaApi(page, 'north.initiator');
    let posted = false;
    page.on('request', (request) => {
      if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/requests') posted = true;
    });

    await page.goto('/requests/new');

    await expect(page.getByRole('heading', { name: 'Новая заявка' })).toBeVisible();
    await page.getByRole('button', { name: 'Создать заявку' }).click();
    await expect(page.getByText('Опишите груз')).toBeVisible();
    await expect(page.getByText('Укажите вес')).toBeVisible();
    await expect(page.getByText('Укажите адрес')).toBeVisible();
    await expect(page.getByText('Укажите срок доставки')).toBeVisible();
    expect(posted).toBe(false);
  });

  test('the calendar offers today in the business zone and not the days before', async ({ page }) => {
    await signInViaApi(page, 'north.initiator');
    await page.goto('/requests/new');

    await page.getByRole('button', { name: 'Срок доставки' }).click();

    const today = page.getByRole('button', { name: dayLabel(0), exact: true });
    await expect(today).toBeVisible();
    await expect(today).not.toHaveAttribute('data-disabled');
    const yesterday = page.getByRole('button', { name: dayLabel(-1), exact: true });
    // Hidden only when the month starts on a Monday and today is the 1st.
    if (await yesterday.count()) {
      await expect(yesterday).toHaveAttribute('data-disabled', 'true');
    }
  });

  test('an operator gets "not found"', async ({ page }) => {
    await signInViaApi(page, 'north.operator');

    await page.goto('/requests/new');

    await expect(page.getByRole('heading', { name: 'Страница не найдена' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Новая заявка' })).toHaveCount(0);
  });
});
