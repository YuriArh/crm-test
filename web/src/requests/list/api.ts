import { keepPreviousData, queryOptions } from '@tanstack/react-query';
import { api, unwrap } from '@/shared/api';
import { requestKeys } from '../keys';
import type { RequestListParams } from './search';

/** Statuses change on their own (the exchange with the carrier runs in the background), so the list refreshes. */
export const LIST_REFRESH_MS = 10_000;

/**
 * One page of the registry. The previous rows stay on screen while another page or filter loads (no
 * flicker); the list is fetched again every 10 s while the tab is visible and at once when the user comes back.
 */
export function requestListQueryOptions(params: RequestListParams) {
  return queryOptions({
    queryKey: requestKeys.list(params),
    queryFn: () => unwrap(api.GET('/api/requests', { params: { query: params } })),
    placeholderData: keepPreviousData,
    refetchInterval: LIST_REFRESH_MS,
    refetchIntervalInBackground: false,
    refetchOnWindowFocus: true,
  });
}
