import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { api, isApiError, unwrap, type RequestCard, type UpdateRequest } from '@/shared/api';
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
      // A request that has left the caller's scope (404) will not come back by asking again.
      if (card === undefined || isFinalCard(card) || isCardGone(query.state.error)) return false;
      return CARD_REFRESH_MS;
    },
    refetchIntervalInBackground: false,
  });
}

/** The request is gone from the caller's scope: someone else's, or no longer visible (NF02). */
export function isCardGone(error: unknown): boolean {
  return isApiError(error) && error.status === 404;
}

/**
 * `PATCH /api/requests/{id}`: the answer replaces the card in the cache and the lists are marked stale.
 * A refresh in flight is cancelled first, so an older card cannot land on top of the answer.
 */
export function useUpdateRequest(id: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: UpdateRequest) => unwrap(api.PATCH('/api/requests/{id}', { params: { path: { id } }, body })),
    onMutate: () => queryClient.cancelQueries({ queryKey: requestKeys.card(id) }),
    onSuccess: (card) => {
      queryClient.setQueryData(requestKeys.card(id), card);
      void queryClient.invalidateQueries({ queryKey: requestKeys.lists });
    },
  });
}
