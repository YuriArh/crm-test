import { describe, expect, it } from 'vitest';
import { formatDate, formatInstant, formatPrice, formatShortDate, formatWeight } from './format';

describe('formatDate', () => {
  it('shows a deadline as it is, without any zone shift', () => {
    expect(formatDate('2026-12-01')).toBe('01.12.2026');
    expect(formatShortDate('2026-12-01')).toBe('01.12');
  });
});

describe('formatInstant', () => {
  const now = new Date('2026-11-14T12:00:00Z');

  it('shows an instant in the business zone', () => {
    expect(formatInstant('2026-11-12T09:15:03Z', 'Europe/Moscow', now)).toBe('12.11, 12:15');
  });

  it('uses the business date across midnight, not the UTC one', () => {
    // 22:30 UTC on the 12th is already 01:30 on the 13th in Moscow.
    expect(formatInstant('2026-11-12T22:30:00Z', 'Europe/Moscow', now)).toBe('13.11, 01:30');
  });

  it('adds the year when it is not the current one', () => {
    expect(formatInstant('2025-12-31T09:00:00Z', 'Europe/Moscow', now)).toBe('31.12.2025, 12:00');
  });
});

describe('numbers', () => {
  // Intl separates thousands and units with no-break spaces; compare with plain ones.
  const plain = (text: string) => text.replace(/\s/g, ' ');

  it('formats weight and price the Russian way', () => {
    expect(plain(formatWeight(12.5))).toBe('12,5 кг');
    expect(plain(formatWeight(20000))).toBe('20 000 кг');
    expect(plain(formatPrice(1375))).toBe('1 375,00 ₽');
  });
});
