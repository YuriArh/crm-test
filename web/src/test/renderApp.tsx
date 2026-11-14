import { MantineProvider } from '@mantine/core';
import { QueryClientProvider } from '@tanstack/react-query';
import { createMemoryHistory, RouterProvider } from '@tanstack/react-router';
import { render } from '@testing-library/react';
import { createQueryClient } from '@/app/queryClient';
import { createAppRouter } from '@/app/router';
import { theme } from '@/app/theme';
import { meKey } from '@/auth/keys';
import type { Me } from '@/shared/api';

/**
 * Renders the whole app at `path`, as main.tsx does, with an in-memory history. `me` answers the
 * current-user query up front (`null` = nobody signed in), so only the requests under test reach MSW.
 */
export function renderApp(path: string, { me }: { me: Me | null }) {
  const queryClient = createQueryClient();
  queryClient.setQueryData(meKey, me);
  const history = createMemoryHistory({ initialEntries: [path] });
  const router = createAppRouter(queryClient, { history });

  render(
    <MantineProvider theme={theme}>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </MantineProvider>,
  );
  return { queryClient, router, history };
}
