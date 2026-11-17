import { request as playwrightRequest, test } from '@playwright/test';

/**
 * The carrier emulator's fault switches (`PUT /admin/faults`, contracts/carrier-api.yaml). They are global to the
 * stand, so only the `faults` project uses them, after every other test and one test at a time.
 */
export const CARRIER_BASE_URL = process.env.CARRIER_BASE_URL ?? 'http://localhost:8081';

export type Faults = Partial<Record<'unavailable' | 'loseResponse' | 'duplicate' | 'delayed' | 'malformed', boolean>>;

const ALL_OFF = { unavailable: false, loseResponse: false, duplicate: false, delayed: false, malformed: false };

export async function setFaults(faults: Faults): Promise<void> {
  const carrier = await playwrightRequest.newContext({ baseURL: CARRIER_BASE_URL });
  try {
    const response = await carrier.put('/admin/faults', { data: { ...ALL_OFF, ...faults } });
    if (!response.ok()) throw new Error(`PUT /admin/faults: ${response.status()} ${await response.text()}`);
  } finally {
    await carrier.dispose();
  }
}

export function allFaultsOff(): Promise<void> {
  return setFaults({});
}

/** Waits until the emulator has received the operation (`GET /admin/operations?operationId=`). */
export async function waitForReceipt(operationId: string, timeoutMs = 30_000): Promise<void> {
  const carrier = await playwrightRequest.newContext({ baseURL: CARRIER_BASE_URL });
  try {
    const until = Date.now() + timeoutMs;
    while (Date.now() < until) {
      const response = await carrier.get('/admin/operations', { params: { operationId } });
      const body = (await response.json()) as { items: unknown[] };
      if (body.items.length > 0) return;
      await new Promise((resolve) => setTimeout(resolve, 250));
    }
    throw new Error(`the carrier did not receive operation ${operationId} in ${timeoutMs} ms`);
  } finally {
    await carrier.dispose();
  }
}

/** Skips the test, with the reason, when the emulator cannot be reached (no stand running). */
export async function requireCarrier(): Promise<void> {
  const carrier = await playwrightRequest.newContext({ baseURL: CARRIER_BASE_URL });
  try {
    const response = await carrier.get('/admin/faults').catch(() => null);
    test.skip(response === null || !response.ok(), `carrier emulator is not reachable at ${CARRIER_BASE_URL}`);
  } finally {
    await carrier.dispose();
  }
}
