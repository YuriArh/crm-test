import { queryOptions } from '@tanstack/react-query';
import { ApiError, api, type Me } from '@/shared/api';

export const meKey = ['auth', 'me'] as const;

/**
 * The signed-in user, `null` when nobody is signed in (`401` here is an answer, not an error). It
 * changes only through sign-in and sign-out, which write it into the cache themselves.
 */
export const meQueryOptions = queryOptions({
  queryKey: meKey,
  queryFn: fetchMe,
  staleTime: Infinity,
});

async function fetchMe(): Promise<Me | null> {
  const { data, error, response } = await api.GET('/api/auth/me');
  if (response.status === 401) {
    return null;
  }
  if (!response.ok || data === undefined) {
    throw ApiError.from(response.status, error);
  }
  return data;
}
