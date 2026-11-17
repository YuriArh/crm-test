import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import type { Me, Problem } from '@/shared/api';

/** The fake dispatch of component tests. Each test adds the handlers it needs with `server.use(...)`. */
export const server = setupServer(
  http.get('*/api/auth/csrf', () => HttpResponse.json({ headerName: 'X-XSRF-TOKEN', token: 'test-token' })),
  // The header's counter of open problems, on every operator screen: none unless a test says otherwise.
  http.get('*/api/problems', ({ request }) =>
    new URL(request.url).searchParams.get('size') === '1'
      ? HttpResponse.json({ items: [], page: 0, size: 1, totalElements: 0, totalPages: 0 })
      : undefined,
  ),
);

export const demoInitiator: Me = {
  id: '0195f000-0000-7000-8000-000000000101',
  username: 'north.initiator',
  displayName: 'Инициатор (Север)',
  role: 'INITIATOR',
  service: { id: '0195f000-0000-7000-8000-000000000001', code: 'NORTH', name: 'Служба Север' },
};

export function problem(status: number, code: string, extra: Partial<Problem> = {}) {
  return HttpResponse.json(
    { title: code, status, code, ...extra },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  );
}
