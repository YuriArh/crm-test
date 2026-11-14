import type { QueryClient } from '@tanstack/react-query';
import { createRouter, type RouterHistory } from '@tanstack/react-router';
import { authenticatedRoute, loginRoute } from '@/auth/routes';
import { homeRoute } from '@/home/routes';
import { ErrorPage } from '@/shared/ui/ErrorPage';
import { NotFoundPage } from '@/shared/ui/NotFoundPage';
import { rootRoute } from './rootRoute';

export type { RouterContext } from './rootRoute';

// Modules add their routes here: public ones under the root, signed-in ones under authenticatedRoute.
const routeTree = rootRoute.addChildren([loginRoute, authenticatedRoute.addChildren([homeRoute])]);

export function createAppRouter(queryClient: QueryClient, options: { history?: RouterHistory } = {}) {
  return createRouter({
    routeTree,
    context: { queryClient },
    history: options.history,
    defaultNotFoundComponent: NotFoundPage,
    defaultErrorComponent: ErrorPage,
    // TanStack Query owns caching; the router always asks it.
    defaultPreloadStaleTime: 0,
  });
}

declare module '@tanstack/react-router' {
  interface Register {
    router: ReturnType<typeof createAppRouter>;
  }
}
