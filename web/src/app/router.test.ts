import { createMemoryHistory } from '@tanstack/react-router';
import { describe, expect, it } from 'vitest';
import { createQueryClient } from './queryClient';
import { createAppRouter } from './router';

async function routeIdsAt(path: string): Promise<string[]> {
  const router = createAppRouter(createQueryClient(), {
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
    expect(await routeIdsAt('/login')).toEqual(['__root__', '/login']);
  });

  it('serves the home page inside the signed-in layout', async () => {
    expect(await routeIdsAt('/')).toEqual(['__root__', '/_authenticated', '/_authenticated/']);
  });

});
