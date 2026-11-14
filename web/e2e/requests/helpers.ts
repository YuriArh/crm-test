import { expect, type APIRequestContext, type Page } from '@playwright/test';
import { signInViaApi } from '../auth/helpers';

export type CreatedRequest = { id: string; number: string; cargoDescription: string };

/** A cargo description no other test (or earlier run) uses, so a test finds its own rows in a shared registry. */
export function uniqueCargo(label: string): string {
  return `${label} ${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 6)}`;
}

/** A deadline `days` ahead (UTC date; at least a day ahead, so it is never before today in Moscow). */
export function futureDate(days = 7): string {
  return new Date(Date.now() + days * 86_400_000).toISOString().slice(0, 10);
}

async function csrfHeader(request: APIRequestContext): Promise<Record<string, string>> {
  const response = await request.get('/api/auth/csrf');
  expect(response.ok()).toBe(true);
  const { headerName, token } = (await response.json()) as { headerName: string; token: string };
  return { [headerName]: token };
}

/**
 * Signs `page` in as `username` (an initiator) and creates a request through the API, as the app would.
 * An address containing "тест-отказ" makes the carrier reject it.
 */
export async function createRequestViaApi(
  page: Page,
  username: string,
  content: Partial<{ cargoDescription: string; weightKg: number; deliveryAddress: string; deadline: string }> = {},
): Promise<CreatedRequest> {
  await signInViaApi(page, username);
  const body = {
    cargoDescription: content.cargoDescription ?? uniqueCargo('Груз'),
    weightKg: content.weightKg ?? 12.5,
    deliveryAddress: content.deliveryAddress ?? 'ул. Ленина, 1',
    deadline: content.deadline ?? futureDate(),
  };
  const response = await page.request.post('/api/requests', {
    headers: { ...(await csrfHeader(page.request)), 'Idempotency-Key': crypto.randomUUID() },
    data: body,
  });
  expect(response.status(), await response.text()).toBe(201);
  const card = (await response.json()) as CreatedRequest;
  return { id: card.id, number: card.number, cargoDescription: card.cargoDescription };
}
