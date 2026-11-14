import { describe, expect, it } from 'vitest';
import { safeNext } from './safeNext';

describe('safeNext', () => {
  it.each(['/', '/requests', '/requests/42?tab=history#top', '/loginx'])('keeps the local path %s', (next) => {
    expect(safeNext(next)).toBe(next);
  });

  it.each([
    ['another site', 'https://evil.example/'],
    ['a protocol-relative URL', '//evil.example'],
    ['a backslash trick', '/\\evil.example'],
    ['a relative path', 'requests'],
    ['the login page', '/login'],
    ['the login page with a query', '/login?next=/requests'],
    ['an empty value', ''],
    ['no value', undefined],
    ['a number', 5],
  ])('sends %s home', (_name, next) => {
    expect(safeNext(next)).toBe('/');
  });
});
