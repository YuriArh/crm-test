// Test data of the problems module (imported by *.test.tsx only): one problem of every kind, as dispatch sends it.
import type { ExchangeProblem, ExchangeProblemPage, OurSide } from '@/shared/api';

export const REQUEST_REF = { id: '0195f3a2-7c41-7d10-9a5e-3f1c2b4d5e60', number: 'DR-000123' };
export const OPERATION_ID = '0195f3a2-7c41-7d10-9a5e-3f1c2b4d5e6f';
/** Our result when a conflict was found: the same as the request's result while it is open. */
export const OUR_RESULT: OurSide = {
  result: { status: 'SUCCEEDED', trackingNumber: 'TRK-00000001', price: 325, reason: null },
};

let sequence = 0;

function base(overrides: Partial<ExchangeProblem>): ExchangeProblem {
  sequence += 1;
  return {
    id: `0195f3a2-0000-7000-8000-${String(sequence).padStart(12, '0')}`,
    kind: 'DELIVERY_UNKNOWN',
    status: 'OPEN',
    details: 'No response in time',
    openedAt: '2026-11-17T11:05:00Z',
    resolvedAt: null,
    request: REQUEST_REF,
    operationId: OPERATION_ID,
    message: null,
    currentResult: null,
    ourSide: null,
    resolvedBy: null,
    decision: null,
    comment: null,
    actions: [],
    ...overrides,
  };
}

export function deliveryUnknown(overrides: Partial<ExchangeProblem> = {}): ExchangeProblem {
  return base({ kind: 'DELIVERY_UNKNOWN', details: 'No response in time', ...overrides });
}

export function deliveryFailed(overrides: Partial<ExchangeProblem> = {}): ExchangeProblem {
  return base({ kind: 'DELIVERY_FAILED', details: 'HTTP 400', ...overrides });
}

export function invalidMessage(overrides: Partial<ExchangeProblem> = {}): ExchangeProblem {
  return base({
    kind: 'INVALID_MESSAGE',
    details: 'price must be greater than 0',
    message: {
      messageId: '0195f3a2-1111-7000-8000-000000000001',
      feedSeq: 42,
      status: 'SUCCEEDED',
      sequence: 2,
      trackingNumber: 'TRK-00000042',
      price: -1,
      reason: null,
      occurredAt: '2026-11-17T11:04:30Z',
      payloadExcerpt: '{"feedSeq":42,"status":"SUCCEEDED","price":-1.00}',
    },
    actions: ['ACKNOWLEDGED'],
    ...overrides,
  });
}

export function messageFailed(overrides: Partial<ExchangeProblem> = {}): ExchangeProblem {
  return invalidMessage({ kind: 'MESSAGE_FAILED', details: 'Processing failed 5 times', ...overrides });
}

/** A record that names no known request. */
export function orphanMessage(overrides: Partial<ExchangeProblem> = {}): ExchangeProblem {
  return invalidMessage({
    request: null,
    operationId: null,
    details: 'operationId is not a UUID',
    message: {
      messageId: null,
      feedSeq: 43,
      status: null,
      sequence: null,
      trackingNumber: null,
      price: null,
      reason: null,
      occurredAt: null,
      payloadExcerpt: '{"feedSeq":43,"operationId":"not-a-uuid"}',
    },
    ...overrides,
  });
}

export function resultConflict(overrides: Partial<ExchangeProblem> = {}): ExchangeProblem {
  return base({
    kind: 'RESULT_CONFLICT',
    details: 'Result of sequence 2 differs from the applied one',
    message: {
      messageId: '0195f3a2-1111-7000-8000-000000000002',
      feedSeq: 44,
      status: 'SUCCEEDED',
      sequence: 2,
      trackingNumber: 'TRK-00000002',
      price: 400,
      reason: null,
      occurredAt: '2026-11-17T11:06:00Z',
      payloadExcerpt: '{"feedSeq":44}',
    },
    currentResult: {
      businessStatus: 'SUCCEEDED',
      result: { trackingNumber: 'TRK-00000001', price: 325, rejectionReason: null, resultAt: '2026-11-17T11:00:00Z' },
    },
    ourSide: OUR_RESULT,
    actions: ['APPLY_CARRIER', 'KEEP_OURS'],
    ...overrides,
  });
}

/** Reconciliation: the carrier's latest answer differs from our result (`feedSeq` is `null`, it is not a feed record). */
export function reconciliationConflict(overrides: Partial<ExchangeProblem> = {}): ExchangeProblem {
  return base({
    kind: 'RECONCILIATION_CONFLICT',
    details: 'The carrier has another result',
    message: {
      messageId: '0195f3a2-1111-7000-8000-000000000003',
      feedSeq: null,
      status: 'REJECTED',
      sequence: 2,
      trackingNumber: null,
      price: null,
      reason: 'Адрес вне зоны доставки',
      occurredAt: '2026-11-17T11:06:00Z',
      payloadExcerpt: '{"state":"EXECUTED"}',
    },
    currentResult: {
      businessStatus: 'SUCCEEDED',
      result: { trackingNumber: 'TRK-00000001', price: 325, rejectionReason: null, resultAt: '2026-11-17T11:00:00Z' },
    },
    ourSide: OUR_RESULT,
    actions: ['APPLY_CARRIER', 'KEEP_OURS'],
    ...overrides,
  });
}

/** Reconciliation: the carrier does not know an operation it acknowledged; we have no result, so a retry fixes it. */
export function externalMissing(overrides: Partial<ExchangeProblem> = {}): ExchangeProblem {
  return base({
    kind: 'EXTERNAL_MISSING',
    details: 'The carrier answered 404 for an acknowledged operation',
    ourSide: { result: null },
    ...overrides,
  });
}

/** Closed by an operator. */
export function resolvedByOperator(overrides: Partial<ExchangeProblem> = {}): ExchangeProblem {
  return resultConflict({
    status: 'RESOLVED',
    resolvedAt: '2026-11-17T11:30:00Z',
    resolvedBy: { id: '0195f000-0000-7000-8000-000000000102', displayName: 'Оператор (Север)' },
    decision: 'KEEP_OURS',
    comment: 'Перевозчик подтвердил прежний номер',
    actions: [],
    ...overrides,
  });
}

/** Closed by the system. */
export function resolvedBySystem(overrides: Partial<ExchangeProblem> = {}): ExchangeProblem {
  return deliveryUnknown({
    status: 'RESOLVED',
    resolvedAt: '2026-11-17T11:10:00Z',
    decision: 'SYSTEM',
    ...overrides,
  });
}

export function problemPage(
  items: ExchangeProblem[],
  { page = 0, size = 20, totalElements = items.length } = {},
): ExchangeProblemPage {
  return { items, page, size, totalElements, totalPages: Math.ceil(totalElements / size) };
}
