import type { QueryClient } from '@tanstack/react-query';
import type { Me } from '@/shared/api';
import { meKey } from './keys';

/**
 * Starts a new session in the cache: drops every query but `me` (data of the previous user must not
 * leak into this one) and stores the new user, `null` after sign-out.
 */
export function replaceSession(queryClient: QueryClient, me: Me | null): void {
  queryClient.removeQueries({
    predicate: (query) => !(query.queryKey[0] === meKey[0] && query.queryKey[1] === meKey[1]),
  });
  queryClient.setQueryData(meKey, me);
}
