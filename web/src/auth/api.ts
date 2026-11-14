import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { ApiError, api, unwrap, type LoginRequest, type Me } from '@/shared/api';
import { meKey } from './keys';
import { replaceSession } from './session';

export { meKey };

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

/**
 * Signs out. The local session ends even when the call fails (e.g. the session was already gone): the
 * cache is cleared before the caller navigates. The server clears the CSRF cookie; the data layer
 * fetches a new token on the next mutating call.
 */
export function useLogout() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => unwrap(api.POST('/api/auth/logout')),
    onSettled: () => replaceSession(queryClient, null),
  });
}

/** Signs in; on success the cache holds only the new user. The CSRF token rotates, the data layer reads it anew. */
export function useLogin() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: LoginRequest) => unwrap(api.POST('/api/auth/login', { body })),
    onSuccess: (me) => replaceSession(queryClient, me),
  });
}
