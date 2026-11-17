// Entries of a request's history for tests (imported by *.test.ts(x) only). Each call makes a new id.
import { http, HttpResponse } from 'msw';
import type { HistoryEntry, HistoryPage } from '@/shared/api';
import { server } from '@/test/server';
import { CARD_ID } from '../testData';

export const OPERATOR = { id: '0195f000-0000-7000-8000-000000000102', displayName: 'Оператор (Север)' };
export const INITIATOR = { id: '0195f000-0000-7000-8000-000000000101', displayName: 'Инициатор (Север)' };

type Entry<K extends HistoryEntry['kind']> = Extract<HistoryEntry, { kind: K }>;

const base = (at: string) => ({ id: crypto.randomUUID(), at });

export const created = (at = '2026-11-12T09:15:03Z'): Entry<'CREATED'> => ({
  ...base(at),
  kind: 'CREATED',
  actor: INITIATOR,
});

export const edited = (o: Partial<Entry<'EDITED'>> = {}): Entry<'EDITED'> => ({
  ...base('2026-11-12T09:30:00Z'),
  kind: 'EDITED',
  actor: OPERATOR,
  version: 3,
  changes: [{ field: 'comment', from: null, to: 'Позвонить заранее' }],
  ...o,
});

export const resultChanged = (o: Partial<Entry<'RESULT_CHANGED'>> = {}): Entry<'RESULT_CHANGED'> => ({
  ...base('2026-11-12T09:15:10Z'),
  kind: 'RESULT_CHANGED',
  actor: null,
  version: 2,
  changes: [
    { field: 'businessStatus', from: 'IN_PROGRESS', to: 'SUCCEEDED' },
    { field: 'trackingNumber', from: null, to: 'TRK-3F1C2B4D' },
    { field: 'price', from: null, to: '612.5' },
  ],
  recordId: crypto.randomUUID(),
  problemId: null,
  ...o,
});

export const acknowledged = (o: Partial<Entry<'DELIVERY_ACKNOWLEDGED'>> = {}): Entry<'DELIVERY_ACKNOWLEDGED'> => ({
  ...base('2026-11-12T09:15:04Z'),
  kind: 'DELIVERY_ACKNOWLEDGED',
  actor: null,
  via: 'RESPONSE',
  ...o,
});

export const retried = (o: Partial<Entry<'DELIVERY_RETRIED'>> = {}): Entry<'DELIVERY_RETRIED'> => ({
  ...base('2026-11-12T09:20:00Z'),
  kind: 'DELIVERY_RETRIED',
  actor: OPERATOR,
  changes: [{ field: 'deliveryStatus', from: 'FAILED', to: 'PENDING' }],
  ...o,
});

export const attempt = (o: Partial<Entry<'SEND_ATTEMPT'>> = {}): Entry<'SEND_ATTEMPT'> => ({
  ...base('2026-11-12T09:15:03Z'),
  kind: 'SEND_ATTEMPT',
  actor: null,
  attemptNo: 1,
  outcome: 'TEMPORARY_ERROR',
  httpStatus: 503,
  error: 'HTTP 503',
  durationMs: 85,
  finishedAt: '2026-11-12T09:15:03Z',
  ...o,
});

export const received = (o: Partial<Entry<'RESULT_RECEIVED'>> = {}): Entry<'RESULT_RECEIVED'> => {
  const id = crypto.randomUUID();
  return {
    ...base('2026-11-12T09:15:10Z'),
    id,
    kind: 'RESULT_RECEIVED',
    actor: null,
    recordId: id,
    source: 'FEED',
    resultStatus: 'SUCCEEDED',
    sequence: 2,
    processingStatus: 'APPLIED',
    reason: null,
    duplicates: 0,
    lastDuplicateAt: null,
    receivedAt: '2026-11-12T09:15:09Z',
    ...o,
  };
};

export const checked = (o: Partial<Entry<'RECONCILIATION'>> = {}): Entry<'RECONCILIATION'> => ({
  ...base('2026-11-12T10:00:00Z'),
  kind: 'RECONCILIATION',
  actor: null,
  trigger: 'SCHEDULED',
  outcome: 'CONFLICT',
  details: 'Results differ',
  ...o,
});

export const problemOpened = (o: Partial<Entry<'PROBLEM_OPENED'>> = {}): Entry<'PROBLEM_OPENED'> => {
  const id = crypto.randomUUID();
  return {
    ...base('2026-11-12T10:00:00Z'),
    id,
    kind: 'PROBLEM_OPENED',
    actor: null,
    problemId: id,
    problemKind: 'RECONCILIATION_CONFLICT',
    ...o,
  };
};

export const problemResolved = (o: Partial<Entry<'PROBLEM_RESOLVED'>> = {}): Entry<'PROBLEM_RESOLVED'> => {
  const id = crypto.randomUUID();
  return {
    ...base('2026-11-12T10:05:00Z'),
    id,
    kind: 'PROBLEM_RESOLVED',
    actor: OPERATOR,
    problemId: id,
    problemKind: 'RECONCILIATION_CONFLICT',
    decision: 'KEEP_OURS',
    comment: 'Наш итог верный',
    ...o,
  };
};

/**
 * Serves `GET /api/requests/{id}/history`: the pages in order, chosen by `before` (the first without it, then the
 * page whose cursor is `cursor-<n>`); returns the requests seen, for assertions.
 */
export function serveHistory(...pages: HistoryEntry[][]) {
  const seen: URL[] = [];
  server.use(
    http.get(`*/api/requests/${CARD_ID}/history`, ({ request }) => {
      const url = new URL(request.url);
      seen.push(url);
      const before = url.searchParams.get('before');
      const index = before === null ? 0 : Number(before.replace('cursor-', ''));
      const page: HistoryPage = {
        items: pages[index] ?? [],
        nextCursor: index + 1 < pages.length ? `cursor-${index + 1}` : null,
      };
      return HttpResponse.json(page);
    }),
  );
  return seen;
}
