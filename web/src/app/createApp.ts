import type { RouterHistory } from '@tanstack/react-router';
import { handleSessionLost } from '@/auth/session';
import { createQueryClient } from './queryClient';
import { createAppRouter } from './router';

/**
 * Builds the query client and the router and ties them together: a lost session (any `401`) sends
 * the user to the login page with the current address as the way back. Used by main.tsx and tests.
 */
export function createApp(options: { history?: RouterHistory } = {}) {
  const queryClient = createQueryClient({
    onUnauthenticated: () =>
      handleSessionLost(queryClient, () => {
        void router.navigate({ to: '/login', search: { next: router.state.location.href } });
      }),
  });
  const router = createAppRouter(queryClient, options);
  return { queryClient, router };
}
