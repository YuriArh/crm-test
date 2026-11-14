import { describe, expect, it } from 'vitest';
import { parseLoginSearch } from './routes';

describe('parseLoginSearch', () => {
  it('keeps next only when it is a string', () => {
    expect(parseLoginSearch({ next: '/requests' })).toEqual({ next: '/requests' });
    expect(parseLoginSearch({ next: 5 })).toEqual({});
    expect(parseLoginSearch({})).toEqual({});
  });
});
