import { useMutation, useQueryClient } from '@tanstack/react-query';
import { requestKeys } from '@/requests/keys';
import { api, unwrap, type ExchangeProblem, type ResolveProblem } from '@/shared/api';
import { problemKeys } from './keys';

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
