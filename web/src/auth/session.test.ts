import { QueryClient } from '@tanstack/react-query';
import { describe, expect, it, vi } from 'vitest';
import type { Me } from '@/shared/api';
import { meKey } from './keys';
import { handleSessionLost, replaceSession } from './session';

const me = { id: 'u1', username: 'north.initiator' } as Me;

describe('replaceSession', () => {
  it('drops the data of the previous user and stores the new one', () => {
    const client = new QueryClient();
    client.setQueryData(meKey, me);
    client.setQueryData(['requests', 'list'], ['r1']);

    replaceSession(client, null);

    expect(client.getQueryData(['requests', 'list'])).toBeUndefined();
    expect(client.getQueryData(meKey)).toBeNull();
  });
});

describe('handleSessionLost', () => {
  it('forgets the user and goes to the login page once for parallel failures', () => {
    const client = new QueryClient();
    client.setQueryData(meKey, me);
    client.setQueryData(['requests', 'list'], ['r1']);
    const goToLogin = vi.fn();

    handleSessionLost(client, goToLogin);
    handleSessionLost(client, goToLogin);

    expect(goToLogin).toHaveBeenCalledTimes(1);
    expect(client.getQueryData(meKey)).toBeNull();
    expect(client.getQueryData(['requests', 'list'])).toBeUndefined();
  });
});
