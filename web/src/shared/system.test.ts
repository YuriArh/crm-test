import { describe, expect, it } from 'vitest';
import { todayIn } from './system';

describe('todayIn', () => {
  it('gives the business date, not the UTC one', () => {
    // 22:30 UTC on the 12th is 01:30 on the 13th in Moscow.
    expect(todayIn('Europe/Moscow', new Date('2026-11-12T22:30:00Z'))).toBe('2026-11-13');
    expect(todayIn('UTC', new Date('2026-11-12T22:30:00Z'))).toBe('2026-11-12');
  });
});
