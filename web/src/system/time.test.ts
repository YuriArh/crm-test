import { describe, expect, it } from 'vitest';

import { formatDate, formatInstant, todayIn } from './time';

describe('time in the business zone', () => {
  it('takes today from the business zone, not from UTC', () => {
    // 22:30 UTC on Nov 12 is already Nov 13 in Moscow (UTC+3).
    const now = Date.parse('2026-11-12T22:30:00Z');
    expect(todayIn('Europe/Moscow', now)).toBe('2026-11-13');
    expect(todayIn('UTC', now)).toBe('2026-11-12');
  });

  it('shows instants in the business zone', () => {
    expect(formatInstant('2026-11-12T22:30:00Z', 'Europe/Moscow')).toBe('13.11.2026, 01:30');
  });

  it('shows a calendar date as it is', () => {
    expect(formatDate('2026-12-01')).toBe('01.12.2026');
  });
});
