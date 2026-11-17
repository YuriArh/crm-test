import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { problemKeys } from '@/problems';
import { api, isApiError, unwrap, type RequestCard, type UpdateRequest } from '@/shared/api';
import { requestKeys } from '../keys';
import { isFinalBusiness, isFinalDelivery } from '../statuses';

/** How often a card that is not final asks the server again (spec §3.4). */
export const CARD_REFRESH_MS = 5_000;

/** A card no longer changes on its own: the result is final and so is the delivery. */
export function isFinalCard(card: RequestCard): boolean {
  return isFinalBusiness(card.businessStatus) && isFinalDelivery(card.deliveryStatus);
}

/** An open problem may still change a final card: a retry runs, the system closes what it can (operators only). */
export function hasOpenProblem(card: RequestCard): boolean {
  return card.problems?.some((problem) => problem.status === 'OPEN') ?? false;
}

/** How often the card is asked again: every 5 s until it is final and has no open problem (web-problems.md §7.3). */
export function cardRefreshInterval(card: RequestCard | undefined, error: unknown): number | false {
  // A request that has left the caller's scope (404) will not come back by asking again.
  if (card === undefined || isCardGone(error)) return false;
  return isFinalCard(card) && !hasOpenProblem(card) ? false : CARD_REFRESH_MS;
}

/**
 * The request card. It is asked again by {@link cardRefreshInterval}, only while the tab is visible
 * (TanStack Query pauses intervals of hidden tabs). The draft of an edit lives apart from this data.
 */
export function requestCardQueryOptions(id: string) {
  return queryOptions({
    queryKey: requestKeys.card(id),
    queryFn: () => unwrap(api.GET('/api/requests/{id}', { params: { path: { id } } })),
    // The loader has just fetched it; mounting the page must not ask again at once.
    staleTime: 1_000,
    refetchInterval: (query) => cardRefreshInterval(query.state.data, query.state.error),
    refetchIntervalInBackground: false,
  });
}

/** Enabled operators of the caller's service, the choices of an assignee. Asked by operators only. */
export const operatorsQueryOptions = queryOptions({
  queryKey: requestKeys.operators,
  queryFn: () => unwrap(api.GET('/api/users/operators')),
  staleTime: 60_000,
});

/** The request is gone from the caller's scope: someone else's, or no longer visible (NF02). */
export function isCardGone(error: unknown): boolean {
  return isApiError(error) && error.status === 404;
}

/**
 * `POST /api/requests/{id}/retry` (operators): a new budget of retries with the same `operationId`. The answer —
 * the card — replaces the one in the cache; the registry and the problems are stale. A replay of an earlier retry
 * with the same key (`Idempotent-Replayed`) is a success too.
 */
export function useRetryDelivery(id: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (idempotencyKey: string) =>
      unwrap(
        api.POST('/api/requests/{id}/retry', {
          params: { path: { id }, header: { 'Idempotency-Key': idempotencyKey } },
        }),
      ),
    onMutate: () => queryClient.cancelQueries({ queryKey: requestKeys.card(id) }),
    onSuccess: (card) => {
      queryClient.setQueryData(requestKeys.card(id), card);
      void queryClient.invalidateQueries({ queryKey: requestKeys.lists });
      void queryClient.invalidateQueries({ queryKey: problemKeys.all });
    },
  });
}

/**
 * `POST /api/requests/{id}/reconcile` (operators): ask the carrier now and act as background reconciliation does.
 * The answer is the recorded check (`ERROR` included). The check may have confirmed the delivery, taken a result
 * over, opened or closed a problem — so the card, the registry and the problems are stale afterwards.
 */
export function useReconcile(id: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (idempotencyKey: string) =>
      unwrap(
        api.POST('/api/requests/{id}/reconcile', {
          params: { path: { id }, header: { 'Idempotency-Key': idempotencyKey } },
        }),
      ),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: requestKeys.card(id) });
      void queryClient.invalidateQueries({ queryKey: requestKeys.lists });
      void queryClient.invalidateQueries({ queryKey: problemKeys.all });
    },
  });
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
