import { describe, expect, it, vi } from 'vitest';
import { ApiError, NetworkError } from '@/shared/api';
import { createQueryClient } from './queryClient';

const unauthenticated = () => new ApiError(401, { title: 'Unauthorized', status: 401, code: 'UNAUTHENTICATED' });

describe('createQueryClient', () => {
  it('reports UNAUTHENTICATED from a query and from a mutation', async () => {
    const onUnauthenticated = vi.fn();
    const client = createQueryClient({ onUnauthenticated });

    await client.fetchQuery({ queryKey: ['x'], queryFn: () => Promise.reject(unauthenticated()) }).catch(() => {});
    await client
      .getMutationCache()
      .build(client, { mutationFn: () => Promise.reject(unauthenticated()) })
      .execute(undefined)
      .catch(() => {});

    expect(onUnauthenticated).toHaveBeenCalledTimes(2);
  });

  it('ignores other failures, including a wrong password', async () => {
    const onUnauthenticated = vi.fn();
    const client = createQueryClient({ onUnauthenticated });
    const failures = [
      new ApiError(401, { title: 'Bad credentials', status: 401, code: 'BAD_CREDENTIALS' }),
      new ApiError(403, { title: 'Forbidden', status: 403, code: 'FORBIDDEN' }),
      new ApiError(404, undefined),
    ];

    for (const [index, failure] of failures.entries()) {
      await client.fetchQuery({ queryKey: ['x', index], queryFn: () => Promise.reject(failure) }).catch(() => {});
    }

    expect(onUnauthenticated).not.toHaveBeenCalled();
  });

  it('retries a query only after a transient failure', async () => {
    const client = createQueryClient();
    const retry = client.getDefaultOptions().queries?.retry as (count: number, error: Error) => boolean;

    expect(retry(0, new NetworkError())).toBe(true);
    expect(retry(0, new ApiError(503, undefined))).toBe(true);
    expect(retry(2, new ApiError(503, undefined))).toBe(false);
    expect(retry(0, unauthenticated())).toBe(false);
  });
});
