import { expect, type Page } from '@playwright/test';

export const PASSWORD = 'demo1234';

export async function login(page: Page, username: string) {
  await page.goto('/login');
  await page.getByLabel('Логин').fill(username);
  await page.getByLabel('Пароль').fill(PASSWORD);
  await page.getByRole('button', { name: 'Войти' }).click();
  await expect(page.getByRole('heading', { name: 'Заявки' })).toBeVisible();
}

export async function logout(page: Page) {
  await page.getByRole('button', { name: 'Выйти' }).click();
  await expect(page.getByRole('heading', { name: 'Вход в диспетчерскую' })).toBeVisible();
}

/** Creates a request through the form and returns its card URL and number. */
export async function createRequest(page: Page, cargo: string) {
  await page.getByRole('link', { name: 'Создать заявку' }).first().click();
  await page.getByLabel('Груз').fill(cargo);
  await page.getByLabel('Вес, кг').fill('12,5');
  await page.getByLabel('Адрес доставки').fill('ул. Ленина, 1');
  await page.getByLabel('Срок доставки').click();
  // Any day of the next month is in the future in every time zone.
  await page.locator('button[data-direction="next"]').click();
  await page
    .locator('table button:not([data-outside]):not([data-disabled])')
    .filter({ hasText: /^15$/ })
    .click();
  await page.getByRole('button', { name: 'Создать' }).click();

  const heading = page.getByRole('heading', { name: /^Заявка DR-\d+$/ });
  await expect(heading).toBeVisible();
  const number = (await heading.textContent())!.replace('Заявка ', '');
  return { url: page.url(), number };
}
