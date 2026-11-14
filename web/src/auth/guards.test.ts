import { QueryClient } from '@tanstack/react-query';
import { isNotFound, isRedirect } from '@tanstack/react-router';
import { describe, expect, it } from 'vitest';
import type { Me } from '@/shared/api';
import { meKey } from './api';
import { redirectIfSignedIn, requireAuth, requireRole } from './guards';

const me: Me = {
  id: 'u1',
  username: 'north.initiator',
  displayName: 'Инициатор (Север)',
  role: 'INITIATOR',
  service: { id: 's1', code: 'NORTH', name: 'Служба Север' },
};

function clientWith(user: Me | null): QueryClient {
  const client = new QueryClient();
  client.setQueryData(meKey, user);
  return client;
}

async function thrown(promise: Promise<unknown>): Promise<unknown> {
  return promise.then(
    () => undefined,
    (error: unknown) => error,
  );
}

describe('requireAuth', () => {
  it('puts the signed-in user into the route context', async () => {
    expect(await requireAuth(clientWith(me), '/')).toEqual({ me });
  });

  it('sends a visitor to the login page with the way back', async () => {
    const error = await thrown(requireAuth(clientWith(null), '/requests/42?tab=history'));

    expect(isRedirect(error)).toBe(true);
    expect(error).toMatchObject({ options: { to: '/login', search: { next: '/requests/42?tab=history' } } });
  });
});

describe('redirectIfSignedIn', () => {
  it('lets a visitor stay on the login page', async () => {
    await expect(redirectIfSignedIn(clientWith(null), '/requests')).resolves.toBeUndefined();
  });

  it('sends a signed-in user on to a safe next', async () => {
    const error = await thrown(redirectIfSignedIn(clientWith(me), '/requests'));

    expect(isRedirect(error)).toBe(true);
    expect(error).toMatchObject({ options: { href: '/requests' } });
  });

  it('never follows next to another site', async () => {
    const error = await thrown(redirectIfSignedIn(clientWith(me), 'https://evil.example'));

    expect(error).toMatchObject({ options: { href: '/' } });
  });
});

describe('requireRole', () => {
  it('lets the right role through and answers not found to anyone else', () => {
    expect(() => requireRole(me, 'INITIATOR')).not.toThrow();

    let error: unknown;
    try {
      requireRole(me, 'OPERATOR');
    } catch (thrown) {
      error = thrown;
    }
    expect(isNotFound(error)).toBe(true);
  });
});
