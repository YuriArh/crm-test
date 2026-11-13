import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useContext } from 'react';

import { api, unwrap } from '../api/client';
import { refreshCsrf } from '../api/csrf';
import { ApiError } from '../api/errors';
import type { Me } from '../api/types';
import { CurrentUserContext } from './currentUserContext';
import { meKey } from './meKey';

/** The signed-in user, `null` when nobody is signed in. */
export function useMe() {
  return useQuery({
    queryKey: meKey,
    queryFn: fetchMe,
    staleTime: Infinity,
  });
}

async function fetchMe(): Promise<Me | null> {
  const { data, error, response } = await api.GET('/api/auth/me');
  if (response.status === 401) {
    return null;
  }
  if (!response.ok || !data) {
    throw ApiError.from(response.status, error);
  }
  return data;
}

/**
 * The signed-in user inside the auth guard. Read from the guard's context rather than from the query:
 * when the session is lost the query turns `null` before the guard has unmounted the screens.
 */
export function useCurrentUser(): Me {
  const me = useContext(CurrentUserContext);
  if (!me) {
    throw new Error('useCurrentUser is used outside of RequireAuth');
  }
  return me;
}

export function useLogin() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: { username: string; password: string }) =>
      unwrap(api.POST('/api/auth/login', { body })),
    onSuccess: (me) => {
      // Another user's cached data must not leak into this session.
      queryClient.removeQueries({ predicate: (query) => query.queryKey[0] !== meKey[0] });
      queryClient.setQueryData(meKey, me);
    },
  });
}

export function useLogout() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => unwrap(api.POST('/api/auth/logout')),
    onSettled: async () => {
      queryClient.removeQueries({ predicate: (query) => query.queryKey[0] !== meKey[0] });
      queryClient.setQueryData(meKey, null);
      // The server cleared the CSRF cookie together with the session.
      await refreshCsrf().catch(() => undefined);
    },
  });
}
