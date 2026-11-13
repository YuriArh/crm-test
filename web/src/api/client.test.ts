import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';

import { problem, server } from '../test/server';
import { api, unwrap } from './client';
import { ApiError, NetworkError } from './errors';

describe('api client', () => {
  it('sends the CSRF token from the cookie on mutating calls', async () => {
    document.cookie = 'XSRF-TOKEN=cookie-token; path=/';
    let header: string | null = null;
    server.use(
      http.post('*/api/auth/logout', ({ request }) => {
        header = request.headers.get('X-XSRF-TOKEN');
        return new HttpResponse(null, { status: 204 });
      }),
    );
    await unwrap(api.POST('/api/auth/logout'));
    expect(header).toBe('cookie-token');
  });

  it('fetches a fresh token and repeats the call once after 403 CSRF_INVALID', async () => {
    document.cookie = 'XSRF-TOKEN=stale; path=/';
    const seen: (string | null)[] = [];
    server.use(
      http.post('*/api/auth/logout', ({ request }) => {
        const token = request.headers.get('X-XSRF-TOKEN');
        seen.push(token);
        return token === 'test-token'
          ? new HttpResponse(null, { status: 204 })
          : problem(403, 'CSRF_INVALID');
      }),
    );
    await unwrap(api.POST('/api/auth/logout'));
    expect(seen).toEqual(['stale', 'test-token']);
  });

  it('does not repeat a real denial (403 FORBIDDEN)', async () => {
    document.cookie = 'XSRF-TOKEN=cookie-token; path=/';
    let calls = 0;
    server.use(
      http.post('*/api/auth/logout', () => {
        calls += 1;
        return problem(403, 'FORBIDDEN');
      }),
    );
    const error = await unwrap(api.POST('/api/auth/logout')).catch((e: unknown) => e);
    expect((error as ApiError).code).toBe('FORBIDDEN');
    expect(calls).toBe(1);
  });

  it('reports a broken connection while fetching the CSRF token as NetworkError', async () => {
    server.use(http.get('*/api/auth/csrf', () => HttpResponse.error()));
    await expect(unwrap(api.POST('/api/auth/logout'))).rejects.toBeInstanceOf(NetworkError);
  });

  it('turns Problem Details into ApiError with code and field errors', async () => {
    server.use(
      http.get('*/api/requests', () =>
        problem(400, 'VALIDATION_FAILED', { errors: [{ field: 'size', message: 'too big' }] }),
      ),
    );
    const error = await unwrap(api.GET('/api/requests')).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).code).toBe('VALIDATION_FAILED');
    expect((error as ApiError).fieldErrors).toEqual([{ field: 'size', message: 'too big' }]);
  });

  it('reports a broken connection as NetworkError', async () => {
    server.use(http.get('*/api/requests', () => HttpResponse.error()));
    await expect(unwrap(api.GET('/api/requests'))).rejects.toBeInstanceOf(NetworkError);
  });
});
