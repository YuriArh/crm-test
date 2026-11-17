import { describe, expect, it } from 'vitest';
import { parseProblemSearch, toProblemApiParams, withKinds, withoutFilters, withPage, withStatus } from './search';

describe('problems list address', () => {
  it('is empty by default: open problems of every kind, page 1', () => {
    expect(parseProblemSearch({})).toEqual({});
    expect(toProblemApiParams({})).toEqual({ status: 'OPEN', page: 0, size: 20 });
  });

  it('reads the status, several kinds and the page', () => {
    const search = parseProblemSearch({ status: 'all', kind: ['RESULT_CONFLICT', 'DELIVERY_FAILED'], page: 3 });
    expect(search).toEqual({ status: 'all', kind: ['DELIVERY_FAILED', 'RESULT_CONFLICT'], page: 3 });
    expect(toProblemApiParams(search)).toEqual({
      status: 'ALL',
      kind: ['DELIVERY_FAILED', 'RESULT_CONFLICT'],
      page: 2,
      size: 20,
    });
    expect(toProblemApiParams(parseProblemSearch({ status: 'resolved' })).status).toBe('RESOLVED');
  });

  it('takes one kind as a string too', () => {
    expect(parseProblemSearch({ kind: 'INVALID_MESSAGE' })).toEqual({ kind: ['INVALID_MESSAGE'] });
  });

  it('drops junk and defaults, so a broken link opens the list', () => {
    expect(parseProblemSearch({ status: 'open', kind: ['LOST', 'LOST'], page: 1 })).toEqual({});
    expect(parseProblemSearch({ status: 'DONE', kind: 42, page: 'abc' })).toEqual({});
    expect(parseProblemSearch({ kind: ['INVALID_MESSAGE', 'INVALID_MESSAGE', 'x'] })).toEqual({ kind: ['INVALID_MESSAGE'] });
  });

  it('goes back to page 1 when a filter changes, and clears filters', () => {
    const search = { status: 'all' as const, kind: ['DELIVERY_FAILED' as const], page: 4 };
    expect(withStatus(search, 'resolved')).toEqual({ status: 'resolved', kind: ['DELIVERY_FAILED'] });
    expect(withStatus(search, 'open')).toEqual({ kind: ['DELIVERY_FAILED'] });
    expect(withKinds(search, [])).toEqual({ status: 'all' });
    expect(withPage(search, 2)).toEqual({ ...search, page: 2 });
    expect(withoutFilters()).toEqual({});
  });
});
