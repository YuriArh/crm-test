import { queryOptions, useQuery } from '@tanstack/react-query';
import { api, unwrap } from '@/shared/api';

/** Fallback until /api/system/info has answered; the server's value always wins. */
export const DEFAULT_BUSINESS_TIMEZONE = 'Europe/Moscow';

/** Public facts about the running dispatch (business time zone); they do not change during a session. */
export const systemInfoQueryOptions = queryOptions({
  queryKey: ['system', 'info'],
  queryFn: () => unwrap(api.GET('/api/system/info')),
  staleTime: Infinity,
});

/** The single business time zone of dates and deadlines (NF07). */
export function useBusinessTimezone(): string {
  const { data } = useQuery(systemInfoQueryOptions);
  return data?.businessTimezone ?? DEFAULT_BUSINESS_TIMEZONE;
}

/** Today's date (`YYYY-MM-DD`) in the business time zone, not the browser's. */
export function todayIn(timeZone: string, now: Date = new Date()): string {
  // en-CA formats as YYYY-MM-DD.
  return new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(now);
}
