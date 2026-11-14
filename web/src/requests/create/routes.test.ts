import { createMemoryHistory } from '@tanstack/react-router';
import { describe, expect, it } from 'vitest';
import { createQueryClient } from '@/app/queryClient';
import { createAppRouter } from '@/app/router';
import { meKey } from '@/auth/keys';
import { systemInfoQueryOptions } from '@/shared/system';
import { demoInitiator } from '@/test/server';

async function leafRouteAt(path: string): Promise<string | undefined> {
  const queryClient = createQueryClient();
  queryClient.setQueryData(meKey, demoInitiator);
  queryClient.setQueryData(systemInfoQueryOptions.queryKey, {
    application: 'dispatch',
    serverTime: '2026-11-14T09:00:00Z',
    businessTimezone: 'Europe/Moscow',
  });
  const router = createAppRouter(queryClient, { history: createMemoryHistory({ initialEntries: [path] }) });
  await router.load();
  return router.state.matches.at(-1)?.routeId;
}

describe('create request route', () => {
  it('matches /requests/new before the card route /requests/$id', async () => {
    expect(await leafRouteAt('/requests/new')).toBe('/_authenticated/requests/new');
  });

  it('leaves other ids to the card route', async () => {
    expect(await leafRouteAt('/requests/0195f3a2-7c41-7d10-9a5e-3f1c2b4d5e60')).toBe('/_authenticated/requests/$id');
  });
});
