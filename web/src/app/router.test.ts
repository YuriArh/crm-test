import { describe, expect, it } from 'vitest';
import { createQueryClient } from './queryClient';
import { createAppRouter } from './router';

describe('createAppRouter', () => {
  it('passes the query client to routes through the context', () => {
    const queryClient = createQueryClient();

    const router = createAppRouter(queryClient);

    expect(router.options.context?.queryClient).toBe(queryClient);
  });
});
