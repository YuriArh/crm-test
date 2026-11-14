import type { QueryClient } from '@tanstack/react-query';
import { createRootRouteWithContext, Outlet } from '@tanstack/react-router';

export type RouterContext = {
  queryClient: QueryClient;
};

// The root of every route. It lives apart from router.tsx so modules can hang their routes on it
// without importing the router itself.
export const rootRoute = createRootRouteWithContext<RouterContext>()({
  component: Outlet,
});
