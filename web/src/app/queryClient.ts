import { QueryClient } from '@tanstack/react-query';
import { isTransient } from '@/shared/api';

export function createQueryClient(): QueryClient {
  return new QueryClient({
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
