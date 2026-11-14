import { createRoute, Outlet } from '@tanstack/react-router';
import { rootRoute } from '@/app/rootRoute';
import { redirectIfSignedIn, requireAuth } from './guards';
import { LoginPage } from './LoginPage';

export type LoginSearch = {
  /** Where to return after signing in; checked by safeNext before use. */
  next?: string;
};

export function parseLoginSearch(search: Record<string, unknown>): LoginSearch {
  return typeof search.next === 'string' ? { next: search.next } : {};
}

export const loginRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/login',
  validateSearch: parseLoginSearch,
  beforeLoad: ({ context, search }) => redirectIfSignedIn(context.queryClient, search.next),
  component: LoginPage,
});

/** The signed-in part of the app: modules hang their routes on this layout. */
export const authenticatedRoute = createRoute({
  getParentRoute: () => rootRoute,
  id: '_authenticated',
  beforeLoad: ({ context, location }) => requireAuth(context.queryClient, location.href),
  component: Outlet,
});
