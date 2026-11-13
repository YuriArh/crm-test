/**
 * Dates and times in the single business time zone (NF07). Instants from the API are UTC; they are shown
 * in the business zone, never in the browser's. Calendar dates (`deadline`) are shown as they are.
 */

const instantFormats = new Map<string, Intl.DateTimeFormat>();

export function formatInstant(iso: string, timeZone: string): string {
  let format = instantFormats.get(timeZone);
  if (!format) {
    format = new Intl.DateTimeFormat('ru-RU', { timeZone, dateStyle: 'short', timeStyle: 'short' });
    instantFormats.set(timeZone, format);
  }
  return format.format(new Date(iso));
}

/** `2026-12-01` → `01.12.2026`. */
export function formatDate(date: string): string {
  const [year, month, day] = date.split('-');
  return `${day}.${month}.${year}`;
}

/** Today's calendar date (`YYYY-MM-DD`) in the given zone at the moment `nowMs`. */
export function todayIn(timeZone: string, nowMs: number): string {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(nowMs);
  const part = (type: Intl.DateTimeFormatPartTypes) => parts.find((p) => p.type === type)?.value ?? '';
  return `${part('year')}-${part('month')}-${part('day')}`;
}

const rubles = new Intl.NumberFormat('ru-RU', { style: 'currency', currency: 'RUB' });
const kilograms = new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 1 });

export function formatPrice(value: number): string {
  return rubles.format(value);
}

export function formatWeight(value: number): string {
  return `${kilograms.format(value)} кг`;
}
