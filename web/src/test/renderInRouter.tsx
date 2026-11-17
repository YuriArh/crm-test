import { MantineProvider } from '@mantine/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryHistory, createRootRoute, createRouter, RouterProvider } from '@tanstack/react-router';
import { render } from '@testing-library/react';
import type { ReactNode } from 'react';
import { cssVariablesResolver, DEFAULT_COLOR_SCHEME, theme } from '@/app/theme';
import { systemInfoQueryOptions } from '@/shared/system';

/**
 * Renders one component, with links working, outside the app's routes: for building blocks that no screen shows
 * on its own yet. Screens are tested with `renderApp`. The business zone is known up front, so no request is made.
 */
export function renderInRouter(ui: ReactNode) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  queryClient.setQueryData(systemInfoQueryOptions.queryKey, {
    application: 'dispatch',
    serverTime: '2026-11-17T12:00:00Z',
    businessTimezone: 'Europe/Moscow',
  });
  const router = createRouter({
    routeTree: createRootRoute({ component: () => ui }),
    history: createMemoryHistory({ initialEntries: ['/'] }),
  });

  return render(
    <MantineProvider theme={theme} cssVariablesResolver={cssVariablesResolver} defaultColorScheme={DEFAULT_COLOR_SCHEME} env="test">
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </MantineProvider>,
  );
}
