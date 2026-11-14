import { MutationCache, QueryCache, QueryClient } from '@tanstack/react-query';
import { isApiError, isTransient } from '@/shared/api';

export type QueryClientOptions = {
  /** Called when any query or mutation fails with `401 UNAUTHENTICATED`: the session is gone. */
  onUnauthenticated?: () => void;
};

export function createQueryClient(options: QueryClientOptions = {}): QueryClient {
  const onError = (error: Error) => {
    if (isApiError(error, 'UNAUTHENTICATED')) options.onUnauthenticated?.();
  };

  return new QueryClient({
    queryCache: new QueryCache({ onError }),
    mutationCache: new MutationCache({ onError }),
    defaultOptions: {
      queries: {
        // Only a broken connection or a server failure may pass on a repeat; 4xx answers never do.
        retry: (failureCount, error) => isTransient(error) && failureCount < 2,
        refetchOnWindowFocus: false,
      },
      // A mutation is repeated only by the user: the outcome of a failed one may be unknown.
      mutations: { retry: false },
    },
  });
}
