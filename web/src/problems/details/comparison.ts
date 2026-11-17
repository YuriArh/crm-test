import { formatPrice } from '@/requests/format';
import type { CurrentResult, ProblemMessage } from '@/shared/api';
import { resultStatusLabel } from './resultStatus';

/**
 * One side of a comparison of two results: the carrier's record or answer, our snapshot (`ResultSnapshot`), or the
 * request's result now. `null` as a whole — that side has no result.
 */
export type ResultFields = {
  status: string | null;
  trackingNumber: string | null;
  price: number | null;
  reason: string | null;
};

export type ComparedRow = { label: string; left: string | null; right: string | null; differs: boolean };

const FIELDS: { label: string; value: (result: ResultFields) => string | null }[] = [
  { label: 'Статус', value: (result) => (result.status === null ? null : resultStatusLabel(result.status)) },
  { label: 'Номер отправления', value: (result) => result.trackingNumber },
  { label: 'Стоимость', value: (result) => (result.price === null ? null : formatPrice(result.price)) },
  { label: 'Причина отказа', value: (result) => result.reason },
];

/** Both sides field by field, in a fixed order; a field empty on both sides is left out. */
export function compareResults(left: ResultFields | null, right: ResultFields | null): ComparedRow[] {
  return FIELDS.map(({ label, value }) => {
    const l = left ? value(left) : null;
    const r = right ? value(right) : null;
    return { label, left: l, right: r, differs: l !== r };
  }).filter((row) => row.left !== null || row.right !== null);
}

/** The names of the differing fields, as they go after «Отличается:». */
export function differences(left: ResultFields | null, right: ResultFields | null): string[] {
  return compareResults(left, right)
    .filter((row) => row.differs)
    .map((row) => row.label.toLowerCase());
}

/** A carrier record as a side; a record without a status brought no result. */
export function fromMessage(message: ProblemMessage | null): ResultFields | null {
  if (message === null || message.status === null) return null;
  return {
    status: message.status,
    trackingNumber: message.trackingNumber,
    price: message.price,
    reason: message.reason,
  };
}

/** The request's result now as a side, its status even when it is not final yet. */
export function fromCurrent(current: CurrentResult | null): ResultFields | null {
  if (current === null) return null;
  return {
    status: current.businessStatus,
    trackingNumber: current.result?.trackingNumber ?? null,
    price: current.result?.price ?? null,
    reason: current.result?.rejectionReason ?? null,
  };
}
