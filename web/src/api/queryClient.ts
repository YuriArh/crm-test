import { MutationCache, QueryCache, QueryClient } from '@tanstack/react-query';
import { notifications } from '@mantine/notifications';

import { meKey } from '../auth/meKey';
import { describeError, isApiError, isTransient } from './errors';

/**
 * `401 UNAUTHENTICATED` from any call means the session is gone (e.g. dispatch restarted): forget the
 * user, and the auth guard sends them to the login page with a return path.
 */
export function createQueryClient(): QueryClient {
  const client: QueryClient = new QueryClient({
    queryCache: new QueryCache({
      onError: (error) => onUnauthenticated(error),
    }),
    mutationCache: new MutationCache({
      onError: (error) => {
        if (onUnauthenticated(error)) {
          return;
        }
        if (isTransient(error)) {
          notifications.show({
            color: 'red',
            title: 'Не удалось выполнить действие',
            message: describeError(error),
          });
        }
      },
    }),
    defaultOptions: {
      queries: {
        retry: (failureCount, error) => isTransient(error) && failureCount < 2,
        staleTime: 5_000,
      },
      mutations: { retry: false },
    },
  });

  function onUnauthenticated(error: unknown): boolean {
    if (!isApiError(error, 'UNAUTHENTICATED')) {
      return false;
    }
    client.removeQueries({ predicate: (query) => query.queryKey[0] !== meKey[0] });
    client.setQueryData(meKey, null);
    return true;
  }

  return client;
}
