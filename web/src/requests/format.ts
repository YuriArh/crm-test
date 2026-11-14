/**
 * Dates of the requests in the business time zone (NF07). A deadline is a calendar date and is never
 * shifted by a zone; an instant (`createdAt`, `resultAt`) is shown in the business zone, not the browser's.
 */

/** `2026-12-01` → `01.12.2026`. */
export function formatDate(date: string): string {
  const [year, month, day] = date.split('-');
  return `${day}.${month}.${year}`;
}

/** `2026-12-01` → `01.12`, for compact places (phone cards). */
export function formatShortDate(date: string): string {
  const [, month, day] = date.split('-');
  return `${day}.${month}`;
}

function yearIn(instant: Date, timeZone: string): string {
  return new Intl.DateTimeFormat('ru-RU', { timeZone, year: 'numeric' }).format(instant);
}

/** An instant in the business zone: `12.11, 12:15`, with the year when it is not the current one. */
export function formatInstant(iso: string, timeZone: string, now: Date = new Date()): string {
  const instant = new Date(iso);
  const withYear = yearIn(instant, timeZone) !== yearIn(now, timeZone);
  return new Intl.DateTimeFormat('ru-RU', {
    timeZone,
    day: '2-digit',
    month: '2-digit',
    ...(withYear ? { year: 'numeric' } : {}),
    hour: '2-digit',
    minute: '2-digit',
  }).format(instant);
}

/** Weight in kilograms with a comma: `12,5 кг`. */
export function formatWeight(kg: number): string {
  return `${new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 1 }).format(kg)} кг`;
}

/** Price in rubles: `1 375,00 ₽`. */
export function formatPrice(rubles: number): string {
  return new Intl.NumberFormat('ru-RU', { style: 'currency', currency: 'RUB' }).format(rubles);
}
