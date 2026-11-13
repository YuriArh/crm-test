import { render } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider, type RouteObject } from 'react-router';

import { createQueryClient } from '../api/queryClient';
import { Providers } from '../App';
import { routes } from '../routes';

export function testQueryClient() {
  const client = createQueryClient();
  const defaults = client.getDefaultOptions();
  client.setDefaultOptions({ ...defaults, queries: { ...defaults.queries, retry: false } });
  return client;
}

/** Renders the app's routes (or given ones) at `path`, with fresh providers. */
export function renderAt(path: string, options: { routes?: RouteObject[] } = {}) {
  const router = createMemoryRouter(options.routes ?? routes, { initialEntries: [path] });
  const queryClient = testQueryClient();
  const user = userEvent.setup();
  const view = render(
    <Providers queryClient={queryClient}>
      <RouterProvider router={router} />
    </Providers>,
  );
  return { ...view, router, queryClient, user };
}
