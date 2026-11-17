import type { QueryClient } from '@tanstack/react-query';
import { createRootRouteWithContext, Outlet } from '@tanstack/react-router';
import type { ComponentType } from 'react';

export type RouterContext = {
  queryClient: QueryClient;
  /**
   * The sections in the header of the signed-in part. The app puts it here, so the auth module that draws the
   * header knows nothing of the modules behind the sections.
   */
  headerNav?: ComponentType;
};

// The root of every route. It lives apart from router.tsx so modules can hang their routes on it
// without importing the router itself.
export const rootRoute = createRootRouteWithContext<RouterContext>()({
  component: Outlet,
});
