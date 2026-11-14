import { createMemoryHistory } from '@tanstack/react-router';
import { describe, expect, it } from 'vitest';
import { meKey } from '@/auth/api';
import type { Me } from '@/shared/api';
import { createQueryClient } from './queryClient';
import { createAppRouter } from './router';

const me: Me = {
  id: 'u1',
  username: 'north.initiator',
  displayName: 'Инициатор (Север)',
  role: 'INITIATOR',
  service: { id: 's1', code: 'NORTH', name: 'Служба Север' },
};

/** Loads `path` with the `me` query already answered, so no request leaves the test. */
async function routeIdsAt(path: string, user: Me | null): Promise<string[]> {
  const queryClient = createQueryClient();
  queryClient.setQueryData(meKey, user);
  const router = createAppRouter(queryClient, {
    history: createMemoryHistory({ initialEntries: [path] }),
  });
  await router.load();
  return router.state.matches.map((match) => match.routeId);
}

describe('createAppRouter', () => {
  it('passes the query client to routes through the context', () => {
    const queryClient = createQueryClient();

    const router = createAppRouter(queryClient);

    expect(router.options.context?.queryClient).toBe(queryClient);
  });

  it('serves /login outside the signed-in layout', async () => {
    expect(await routeIdsAt('/login', null)).toEqual(['__root__', '/login']);
  });

  it('serves the registry inside the signed-in layout', async () => {
    expect(await routeIdsAt('/requests', me)).toEqual(['__root__', '/_authenticated', '/_authenticated/requests']);
  });

  // `/` → `/requests` is followed only in a browser: see RequestListPage.test.tsx.
});
