import { createRoute, redirect } from '@tanstack/react-router';
import { authenticatedRoute } from '@/auth';
import { systemInfoQueryOptions } from '@/shared/system';
import { requestListQueryOptions } from './api';
import { RequestListPage } from './RequestListPage';
import { parseListSearch, toApiParams } from './search';

/** The registry: the main screen of the signed-in part. Its state lives in the address (search.ts). */
export const requestListRoute = createRoute({
  getParentRoute: () => authenticatedRoute,
  path: '/requests',
  validateSearch: parseListSearch,
  loaderDeps: ({ search }) => search,
  // Starts loading before the screen renders; the screen reads the same query and shows its own
  // skeleton and error states, so a failure here does not replace the page with the error page.
  loader: ({ context, deps }) => {
    void context.queryClient.prefetchQuery(requestListQueryOptions(toApiParams(deps, context.me.role)));
    void context.queryClient.prefetchQuery(systemInfoQueryOptions);
  },
  component: RequestListPage,
});

/** `/` is the registry. */
export const homeRedirectRoute = createRoute({
  getParentRoute: () => authenticatedRoute,
  path: '/',
  beforeLoad: () => {
    throw redirect({ to: '/requests', replace: true });
  },
});
