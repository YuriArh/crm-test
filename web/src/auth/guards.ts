import type { QueryClient } from '@tanstack/react-query';
import { redirect } from '@tanstack/react-router';
import type { Me } from '@/shared/api';
import { meQueryOptions } from './api';
import { safeNext } from './safeNext';

/**
 * `beforeLoad` of the signed-in layout: without a user, go to the login page and come back to `href`
 * afterwards. The user goes into the route context for {@link useCurrentUser}. A failure of
 * `/api/auth/me` (network, 5xx) is thrown as is and shows the error page, not the login page.
 */
export async function requireAuth(queryClient: QueryClient, href: string): Promise<{ me: Me }> {
  const me = await queryClient.ensureQueryData(meQueryOptions);
  if (!me) {
    throw redirect({ to: '/login', search: { next: href } });
  }
  return { me };
}

/** `beforeLoad` of the login page: a signed-in user goes on to `next` right away. */
export async function redirectIfSignedIn(queryClient: QueryClient, next: unknown): Promise<void> {
  const me = await queryClient.ensureQueryData(meQueryOptions);
  if (me) {
    throw redirect({ href: safeNext(next) });
  }
}
