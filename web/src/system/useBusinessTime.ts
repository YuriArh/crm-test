import { useQuery } from '@tanstack/react-query';

import { api, unwrap } from '../api/client';
import { formatInstant, todayIn } from './time';

interface BusinessClock {
  timeZone: string;
  /** Server clock minus browser clock, so "today" follows the server even if the browser clock is off. */
  offsetMs: number;
}

export function useBusinessClock() {
  return useQuery({
    queryKey: ['system-info'],
    queryFn: async (): Promise<BusinessClock> => {
      const sentAt = Date.now();
      const info = await unwrap(api.GET('/api/system/info'));
      const receivedAt = Date.now();
      return {
        timeZone: info.businessTimezone,
        offsetMs: Date.parse(info.serverTime) - (sentAt + receivedAt) / 2,
      };
    },
    staleTime: Infinity,
  });
}

/** Formatting helpers bound to the business time zone; until it is known, UTC is used. */
export function useBusinessTime() {
  const { data } = useBusinessClock();
  const timeZone = data?.timeZone ?? 'UTC';
  const offsetMs = data?.offsetMs ?? 0;
  return {
    ready: data !== undefined,
    timeZone,
    formatInstant: (iso: string) => formatInstant(iso, timeZone),
    today: () => todayIn(timeZone, Date.now() + offsetMs),
  };
}
