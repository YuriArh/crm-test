import { describe, expect, it } from 'vitest';
import {
  activeFilterCount,
  formatPageRange,
  parseListSearch,
  toApiParams,
  withFilters,
  withoutFilters,
  withPage,
  withSort,
} from './search';

describe('parseListSearch', () => {
  it('reads a full address', () => {
    expect(
      parseListSearch({ result: 'SUCCEEDED', delivery: 'FAILED', overdue: 1, sort: 'deadline', page: 2 }),
    ).toEqual({ result: 'SUCCEEDED', delivery: 'FAILED', overdue: 1, sort: 'deadline', page: 2 });
  });

  it('accepts values as strings too', () => {
    expect(parseListSearch({ overdue: '1', page: '3' })).toEqual({ overdue: 1, page: 3 });
  });

  it('drops defaults, so a clean address stays clean', () => {
    expect(parseListSearch({ sort: 'new', page: 1 })).toEqual({});
    expect(parseListSearch({})).toEqual({});
  });

  it('drops garbage instead of failing', () => {
    expect(
      parseListSearch({ result: 'foo', delivery: 42, overdue: 'yes', sort: 'price', page: -1, extra: 'x' }),
    ).toEqual({});
    expect(parseListSearch({ page: 2.5 })).toEqual({});
    expect(parseListSearch({ page: 'abc' })).toEqual({});
    expect(parseListSearch({ page: 0 })).toEqual({});
  });
});

describe('toApiParams', () => {
  it('sends the defaults: first page of 20, newest first', () => {
    expect(toApiParams({}, 'OPERATOR')).toEqual({ page: 0, size: 20, sort: 'createdAt,desc' });
  });

  it('maps every filter and the page (from 0 in the API)', () => {
    expect(toApiParams({ result: 'REJECTED', delivery: 'UNKNOWN', overdue: 1, page: 3 }, 'OPERATOR')).toEqual({
      businessStatus: 'REJECTED',
      deliveryStatus: 'UNKNOWN',
      overdue: true,
      page: 2,
      size: 20,
      sort: 'createdAt,desc',
    });
  });

  it('never sends the delivery filter for an initiator', () => {
    expect(toApiParams({ delivery: 'FAILED', result: 'AWAITING' }, 'INITIATOR')).toEqual({
      businessStatus: 'AWAITING',
      page: 0,
      size: 20,
      sort: 'createdAt,desc',
    });
  });

  it('maps every sort', () => {
    expect(toApiParams({ sort: 'old' }, 'OPERATOR').sort).toBe('createdAt,asc');
    expect(toApiParams({ sort: 'deadline' }, 'OPERATOR').sort).toBe('deadline,asc');
    expect(toApiParams({ sort: 'number' }, 'OPERATOR').sort).toBe('number,desc');
  });
});

describe('changing the address', () => {
  it('goes back to the first page when a filter changes', () => {
    expect(withFilters({ page: 4, sort: 'old' }, { result: 'SUCCEEDED' })).toEqual({ result: 'SUCCEEDED', sort: 'old' });
  });

  it('clears a filter with undefined', () => {
    expect(withFilters({ result: 'SUCCEEDED', overdue: 1 }, { overdue: undefined })).toEqual({ result: 'SUCCEEDED' });
  });

  it('goes back to the first page when the sort changes, and drops the default sort', () => {
    expect(withSort({ page: 2, sort: 'old' }, 'deadline')).toEqual({ sort: 'deadline' });
    expect(withSort({ sort: 'old' }, 'new')).toEqual({});
  });

  it('keeps filters when the page changes', () => {
    expect(withPage({ result: 'REJECTED' }, 3)).toEqual({ result: 'REJECTED', page: 3 });
    expect(withPage({ result: 'REJECTED', page: 3 }, 1)).toEqual({ result: 'REJECTED' });
  });

  it('resets every filter but keeps the sort', () => {
    expect(withoutFilters({ result: 'REJECTED', delivery: 'FAILED', overdue: 1, sort: 'number', page: 2 })).toEqual({
      sort: 'number',
    });
  });
});

describe('activeFilterCount', () => {
  it('counts filters, not the sort or the page', () => {
    expect(activeFilterCount({ result: 'REJECTED', overdue: 1, sort: 'old', page: 2 }, 'OPERATOR')).toBe(2);
    expect(activeFilterCount({ delivery: 'FAILED' }, 'OPERATOR')).toBe(1);
  });

  it('ignores the delivery filter of an initiator', () => {
    expect(activeFilterCount({ delivery: 'FAILED' }, 'INITIATOR')).toBe(0);
  });
});

describe('formatPageRange', () => {
  it('shows the rows of the page and the total', () => {
    expect(formatPageRange(0, 20, 128)).toBe('1–20 из 128');
    expect(formatPageRange(6, 20, 128)).toBe('121–128 из 128');
    expect(formatPageRange(0, 20, 0)).toBe('0 из 0');
  });
});
