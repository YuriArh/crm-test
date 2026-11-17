import { keepPreviousData, queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { requestKeys } from '@/requests/keys';
import { api, unwrap, type ExchangeProblem, type ResolveProblem } from '@/shared/api';
import { problemKeys } from './keys';
import type { ProblemListParams } from './list/search';

/** Problems appear and close on their own (the exchange runs in the background), so the screens refresh. */
export const PROBLEMS_REFRESH_MS = 10_000;
export const PROBLEM_REFRESH_MS = 5_000;

/** One page of problems; the previous rows stay while another page or filter loads, like the registry. */
export function problemsQueryOptions(params: ProblemListParams) {
  return queryOptions({
    queryKey: problemKeys.list(params),
    queryFn: () => unwrap(api.GET('/api/problems', { params: { query: params } })),
    placeholderData: keepPreviousData,
    refetchInterval: PROBLEMS_REFRESH_MS,
    refetchIntervalInBackground: false,
    refetchOnWindowFocus: true,
  });
}

/** How often the header asks for the number of open problems. */
export const OPEN_COUNT_REFRESH_MS = 30_000;

/**
 * The number of open problems of the operator's scope (the header's badge): one row is enough, the total is the
 * answer. It is a hint, so a failure shows nothing; it is asked again every 30 s while the tab is visible.
 */
export const openCountQueryOptions = queryOptions({
  queryKey: problemKeys.openCount,
  queryFn: async () =>
    (await unwrap(api.GET('/api/problems', { params: { query: { status: 'OPEN', size: 1 } } }))).totalElements,
  refetchInterval: OPEN_COUNT_REFRESH_MS,
  refetchIntervalInBackground: false,
});

/** One problem (the panel); asked again every 5 s while it is open, until it is closed. */
export function problemQueryOptions(id: string) {
  return queryOptions({
    queryKey: problemKeys.one(id),
    queryFn: () => unwrap(api.GET('/api/problems/{id}', { params: { path: { id } } })),
    refetchInterval: (query) => (query.state.data?.status === 'OPEN' ? PROBLEM_REFRESH_MS : false),
    refetchIntervalInBackground: false,
  });
}

export type ResolveVariables = { problemId: string; body: ResolveProblem; idempotencyKey: string };

/**
 * `POST /api/problems/{id}/resolve`. The answer is the problem after the decision; the problems and the requests
 * (a card's problems, the registry's counts, and the result after `APPLY_CARRIER`) are stale afterwards. A replay of
 * an earlier decision with the same key (`Idempotent-Replayed`) is a success too.
 */
export function useResolveProblem() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ problemId, body, idempotencyKey }: ResolveVariables) =>
      unwrap(
        api.POST('/api/problems/{id}/resolve', {
          params: { path: { id: problemId }, header: { 'Idempotency-Key': idempotencyKey } },
          body,
        }),
      ),
    onSuccess: (problem: ExchangeProblem) => {
      queryClient.setQueryData(problemKeys.one(problem.id), problem);
      void invalidateAfterDecision(queryClient);
    },
  });
}

/** Everything a decision may change: problems (lists, the count) and requests (cards, the registry). */
export function invalidateAfterDecision(queryClient: ReturnType<typeof useQueryClient>) {
  return Promise.all([
    queryClient.invalidateQueries({ queryKey: problemKeys.lists }),
    queryClient.invalidateQueries({ queryKey: problemKeys.openCount }),
    queryClient.invalidateQueries({ queryKey: requestKeys.all }),
  ]);
}
