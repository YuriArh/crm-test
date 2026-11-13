import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';

import type { Me } from '../api/types';
import { initiator } from './fixtures';

export function problem(status: number, code: string, extra: Record<string, unknown> = {}) {
  return HttpResponse.json(
    { title: code, status, code, detail: code, ...extra },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  );
}

/** Handlers every screen needs: CSRF, the current user, system info. */
export function baseHandlers(me: Me | null = initiator) {
  return [
    http.get('*/api/auth/csrf', () => {
      document.cookie = 'XSRF-TOKEN=test-token; path=/';
      return HttpResponse.json({ headerName: 'X-XSRF-TOKEN', token: 'test-token' });
    }),
    http.get('*/api/auth/me', () => (me ? HttpResponse.json(me) : problem(401, 'UNAUTHENTICATED'))),
    http.get('*/api/system/info', () =>
      HttpResponse.json({
        application: 'dispatch',
        serverTime: new Date().toISOString(),
        businessTimezone: 'Europe/Moscow',
      }),
    ),
  ];
}

export const server = setupServer(...baseHandlers());
