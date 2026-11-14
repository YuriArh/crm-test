import { queryOptions } from '@tanstack/react-query';
import { api, unwrap, type RequestCard } from '@/shared/api';
import { requestKeys } from '../keys';
import { isFinalBusiness, isFinalDelivery } from '../statuses';

/** How often a card that is not final asks the server again (spec §3.4). */
export const CARD_REFRESH_MS = 5_000;

/** A card no longer changes on its own: the result is final and so is the delivery. */
export function isFinalCard(card: RequestCard): boolean {
  return isFinalBusiness(card.businessStatus) && isFinalDelivery(card.deliveryStatus);
}

/**
 * The request card. While it is not final it is asked again every 5 s, only while the tab is visible
 * (TanStack Query pauses intervals of hidden tabs). The draft of an edit lives apart from this data.
 */
export function requestCardQueryOptions(id: string) {
  return queryOptions({
    queryKey: requestKeys.card(id),
    queryFn: () => unwrap(api.GET('/api/requests/{id}', { params: { path: { id } } })),
    // The loader has just fetched it; mounting the page must not ask again at once.
    staleTime: 1_000,
    refetchInterval: (query) => {
      const card = query.state.data;
      return card === undefined || isFinalCard(card) ? false : CARD_REFRESH_MS;
    },
    refetchIntervalInBackground: false,
  });
}
