import { describe, expect, it } from 'vitest';
import { formatPrice } from '@/requests/format';
import { compareResults, differences, fromCurrent, fromMessage, type ResultFields } from './comparison';

const OURS: ResultFields = { status: 'SUCCEEDED', trackingNumber: 'TRK-00000001', price: 325, reason: null };
const CARRIERS: ResultFields = { status: 'SUCCEEDED', trackingNumber: 'TRK-00000002', price: 400, reason: null };
const REJECTED: ResultFields = { status: 'REJECTED', trackingNumber: null, price: null, reason: 'Адрес вне зоны' };

describe('compareResults', () => {
  it('lists the fields in a fixed order as words and marks the ones that differ', () => {
    expect(compareResults(CARRIERS, OURS)).toEqual([
      { label: 'Статус', left: 'Выполнена', right: 'Выполнена', differs: false },
      { label: 'Номер отправления', left: 'TRK-00000002', right: 'TRK-00000001', differs: true },
      { label: 'Стоимость', left: formatPrice(400), right: formatPrice(325), differs: true },
    ]);
  });

  it('keeps a field empty on one side and leaves out the ones empty on both', () => {
    expect(compareResults(REJECTED, OURS)).toEqual([
      { label: 'Статус', left: 'Отказ', right: 'Выполнена', differs: true },
      { label: 'Номер отправления', left: null, right: 'TRK-00000001', differs: true },
      { label: 'Стоимость', left: null, right: formatPrice(325), differs: true },
      { label: 'Причина отказа', left: 'Адрес вне зоны', right: null, differs: true },
    ]);
  });

  it('marks every field of the side that has a result when the other has none', () => {
    expect(compareResults(null, OURS).every((row) => row.differs && row.left === null)).toBe(true);
  });

  it('finds nothing different in equal results', () => {
    expect(compareResults(OURS, { ...OURS }).some((row) => row.differs)).toBe(false);
  });
});

describe('differences', () => {
  it('names the differing fields in the order of the rows', () => {
    expect(differences(CARRIERS, OURS)).toEqual(['номер отправления', 'стоимость']);
  });

  it('is empty for equal results and for two missing ones', () => {
    expect(differences(OURS, { ...OURS })).toEqual([]);
    expect(differences(null, null)).toEqual([]);
  });
});

describe('sides from the API', () => {
  it('reads a carrier record: no status means no result', () => {
    const record = {
      messageId: 'm',
      feedSeq: null,
      status: 'SUCCEEDED',
      sequence: 2,
      trackingNumber: 'TRK-00000002',
      price: 400,
      reason: null,
      occurredAt: null,
      payloadExcerpt: '{}',
    };
    expect(fromMessage(record)).toEqual(CARRIERS);
    expect(fromMessage({ ...record, status: null })).toBeNull();
    expect(fromMessage(null)).toBeNull();
  });

  it('reads the request’s result now, including a status that is not final yet', () => {
    expect(
      fromCurrent({
        businessStatus: 'SUCCEEDED',
        result: { trackingNumber: 'TRK-00000001', price: 325, rejectionReason: null, resultAt: '2026-11-17T11:00:00Z' },
      }),
    ).toEqual(OURS);
    expect(fromCurrent({ businessStatus: 'IN_PROGRESS', result: null })).toEqual({
      status: 'IN_PROGRESS',
      trackingNumber: null,
      price: null,
      reason: null,
    });
    expect(fromCurrent(null)).toBeNull();
  });
});
