import { expect, type Page, type Response } from '@playwright/test';

/** A date in Moscow (the stand's business zone), `offsetDays` from today, as the calendar labels its days. */
export function dayLabel(offsetDays = 0): string {
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

export type FormContent = { cargo: string; weight?: string; address?: string };

/** Fills the open create form; the deadline is today in the business zone. */
export async function fillCreateForm(page: Page, { cargo, weight = '12,5', address = 'ул. Ленина, 1' }: FormContent) {
  await page.getByRole('textbox', { name: 'Груз', exact: true }).fill(cargo);
  await page.getByRole('textbox', { name: 'Вес, кг' }).fill(weight);
  await page.getByRole('textbox', { name: 'Адрес доставки', exact: true }).fill(address);
  await page.getByRole('button', { name: 'Срок доставки' }).click();
  await page.getByRole('button', { name: dayLabel(0), exact: true }).click();
}

/** Opens «Проверьте данные» for a filled form. */
export async function openConfirm(page: Page) {
  await page.getByRole('button', { name: 'Создать заявку' }).click();
  const dialog = page.getByRole('dialog', { name: 'Проверьте данные' });
  await expect(dialog).toBeVisible();
  return dialog;
}

function isCreate(response: Response): boolean {
  return response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/requests';
}

/** Every answer of POST /api/requests the page gets, to tell new requests from replays. */
export function recordCreates(page: Page) {
  const answers: { status: number; replayed: boolean; id: string | undefined }[] = [];
  const pending: Promise<void>[] = [];
  page.on('response', (response) => {
    if (!isCreate(response)) return;
    pending.push(
      (async () => {
        const body = (await response.json().catch(() => undefined)) as { id?: string } | undefined;
        answers.push({
          status: response.status(),
          replayed: response.headers()['idempotent-replayed'] === 'true',
          id: body?.id,
        });
      })(),
    );
  });
  return {
    answers,
    /** Answers that created a request (not replays). */
    async created() {
      await Promise.all(pending);
      return answers.filter((answer) => answer.status === 201 && !answer.replayed);
    },
  };
}

/** The id from `/requests/{id}` once the app has gone to the card. */
export async function cardIdFromUrl(page: Page): Promise<string> {
  await expect(page).toHaveURL(/\/requests\/[0-9a-f-]{36}$/);
  return new URL(page.url()).pathname.split('/').at(-1)!;
}
