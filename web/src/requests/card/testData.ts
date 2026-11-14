// Fixtures of the card's component tests (imported by *.test.tsx only).
import { http, HttpResponse } from 'msw';
import type { Me, RequestCard, UserSummary } from '@/shared/api';
import { demoInitiator, server } from '@/test/server';

export const CARD_ID = '0195f3a2-7c41-7d10-9a5e-3f1c2b4d5e60';

export const demoOperator: Me = {
  ...demoInitiator,
  id: '0195f000-0000-7000-8000-000000000102',
  username: 'north.operator',
  displayName: 'Оператор (Север)',
  role: 'OPERATOR',
};

export const operators: UserSummary[] = [
  { id: demoOperator.id, username: 'north.operator', displayName: 'Оператор (Север)' },
  { id: '0195f000-0000-7000-8000-000000000103', username: 'north.operator2', displayName: 'Второй оператор' },
];

/** A request still in the works, as an initiator sees it (no `exchange`). */
export function pendingCard(overrides: Partial<RequestCard> = {}): RequestCard {
  return {
    id: CARD_ID,
    number: 'DR-000123',
    cargoDescription: 'Паллеты',
    weightKg: 12.5,
    deliveryAddress: 'ул. Ленина, 1',
    deadline: '2026-12-01',
    comment: null,
    owner: { id: demoInitiator.id, displayName: 'Инициатор (Север)' },
    assignee: null,
    businessStatus: 'IN_PROGRESS',
    result: null,
    deliveryStatus: 'UNKNOWN',
    operationId: '0195f3a2-7c41-7d10-9a5e-3f1c2b4d5e6f',
    overdue: false,
    createdAt: '2026-11-12T09:15:03Z',
    updatedAt: '2026-11-12T09:16:00Z',
    version: 1,
    ...overrides,
  };
}

export function succeededCard(overrides: Partial<RequestCard> = {}): RequestCard {
  return pendingCard({
    businessStatus: 'SUCCEEDED',
    deliveryStatus: 'ACKNOWLEDGED',
    result: { trackingNumber: 'TRK-3F1C2B4D', price: 612.5, rejectionReason: null, resultAt: '2026-11-12T09:15:09Z' },
    version: 3,
    ...overrides,
  });
}

export function rejectedCard(overrides: Partial<RequestCard> = {}): RequestCard {
  return pendingCard({
    businessStatus: 'REJECTED',
    deliveryStatus: 'ACKNOWLEDGED',
    result: { trackingNumber: null, price: null, rejectionReason: 'Адрес вне зоны доставки', resultAt: '2026-11-12T09:15:09Z' },
    version: 3,
    ...overrides,
  });
}

/** The operator's view: the same card with the exchange block. */
export function withExchange(card: RequestCard): RequestCard {
  return {
    ...card,
    exchange: {
      attempts: 2,
      nextAttemptAt: null,
      lastError: 'Перевозчик недоступен (503)',
      acknowledgedAt: '2026-11-12T09:15:04Z',
      acknowledgedVia: 'RESPONSE',
    },
  };
}

/**
 * Serves `GET /api/requests/{id}` (the card returned by `next()`, so tests can change it between calls)
 * and the system info; returns a counter of card requests.
 */
export function serveCard(next: RequestCard | (() => RequestCard)) {
  const calls = { count: 0 };
  server.use(
    http.get('*/api/system/info', () =>
      HttpResponse.json({ application: 'dispatch', serverTime: '2026-11-14T12:00:00Z', businessTimezone: 'Europe/Moscow' }),
    ),
    http.get(`*/api/requests/${CARD_ID}`, () => {
      calls.count += 1;
      return HttpResponse.json(typeof next === 'function' ? next() : next);
    }),
  );
  return calls;
}

export function serveOperators(list: UserSummary[] = operators) {
  const calls = { count: 0 };
  server.use(
    http.get('*/api/users/operators', () => {
      calls.count += 1;
      return HttpResponse.json(list);
    }),
  );
  return calls;
}

export const cardPath = `/requests/${CARD_ID}`;
