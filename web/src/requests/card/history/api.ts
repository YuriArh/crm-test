import { infiniteQueryOptions } from '@tanstack/react-query';
import { api, unwrap, type RequestCard } from '@/shared/api';
import { requestKeys } from '../../keys';

/** Entries per page of the history. */
export const HISTORY_PAGE = 50;

/**
 * `GET /api/requests/{id}/history`, newest first, page by page: «Показать ещё» asks for the page before the last
 * one shown. The server decides what the viewer sees (docs/specs/request-history.md §2.4).
 */
export function historyQueryOptions(id: string) {
  return infiniteQueryOptions({
    queryKey: requestKeys.history(id),
    queryFn: ({ pageParam }) =>
      unwrap(
        api.GET('/api/requests/{id}/history', {
          params: { path: { id }, query: { limit: HISTORY_PAGE, ...(pageParam ? { before: pageParam } : {}) } },
        }),
      ),
    initialPageParam: null as string | null,
    getNextPageParam: (page) => page.nextCursor,
  });
}

/**
 * What of the card changes when its history gets a new entry: an edit or a result (version), the delivery, an
 * attempt, a problem, a check. The history is asked again when this changes.
 */
export function historySignature(card: RequestCard): string {
  return [
    card.version,
    card.deliveryStatus,
    card.exchange?.attempts ?? '',
    card.exchange?.nextAttemptAt ?? '',
    card.exchange?.acknowledgedAt ?? '',
    (card.problems ?? []).map((problem) => `${problem.id}:${problem.status}`).join(','),
    card.reconciliations?.[0]?.id ?? '',
  ].join('|');
}
