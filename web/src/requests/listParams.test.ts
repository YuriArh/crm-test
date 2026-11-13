import { describe, expect, it } from 'vitest';

import { parseListParams, toSearch } from './listParams';

describe('registry URL parameters', () => {
  it('round-trips filters, page and sort', () => {
    const search = new URLSearchParams('businessStatus=REJECTED&overdue=true&page=3&sort=deadline,asc');
    const params = parseListParams(search);
    expect(params).toEqual({
      businessStatus: 'REJECTED',
      deliveryStatus: undefined,
      overdue: true,
      page: 3,
      sort: 'deadline,asc',
    });
    expect(toSearch(params).toString()).toBe(search.toString());
  });

  it('ignores invalid values instead of sending them to the server', () => {
    const params = parseListParams(new URLSearchParams('businessStatus=NOPE&page=-1&sort=drop'));
    expect(params).toEqual({
      businessStatus: undefined,
      deliveryStatus: undefined,
      overdue: undefined,
      page: 1,
      sort: 'createdAt,desc',
    });
    expect(toSearch(params).toString()).toBe('');
  });
});
