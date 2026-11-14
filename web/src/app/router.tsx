import type { QueryClient } from '@tanstack/react-query';
import { createRootRouteWithContext, createRouter, Outlet } from '@tanstack/react-router';

export type RouterContext = {
  queryClient: QueryClient;
};

const rootRoute = createRootRouteWithContext<RouterContext>()({
  component: Outlet,
});

// Modules will add their routes here.
const routeTree = rootRoute.addChildren([]);

export function createAppRouter(queryClient: QueryClient) {
  return createRouter({
    routeTree,
    context: { queryClient },
    // TanStack Query owns caching; the router always asks it.
    defaultPreloadStaleTime: 0,
  });
}

declare module '@tanstack/react-router' {
  interface Register {
    router: ReturnType<typeof createAppRouter>;
  }
}
