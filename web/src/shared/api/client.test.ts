import { describe, expect, it } from 'vitest';
import { CSRF_HEADER, createApiClient, readCookie, unwrap } from './client';
import { ApiError, NetworkError } from './errors';

const BASE = 'http://dispatch.test';

type Call = { method: string; path: string; csrf: string | null };

/** A fake dispatch: answers by a handler and keeps the `XSRF-TOKEN` cookie like a browser would. */
function fakeServer(handler: (call: Call, index: number) => Response) {
  const calls: Call[] = [];
  let cookies = '';
  const fetch = async (input: RequestInfo | URL): Promise<Response> => {
    const request = input as Request;
    const call = {
      method: request.method,
      path: new URL(request.url).pathname,
      csrf: request.headers.get(CSRF_HEADER),
    };
    calls.push(call);
    if (call.path === '/api/auth/csrf') {
      cookies = `JSESSIONID=s; XSRF-TOKEN=token-${calls.length}`;
      return json(200, { token: `token-${calls.length}` });
    }
    return handler(call, calls.length - 1);
  };
  const client = createApiClient({ baseUrl: BASE, fetch, readCookies: () => cookies });
  return { client, calls, setCookies: (value: string) => (cookies = value) };
}

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  });
}

function problem(status: number, code: string, extra: object = {}): Response {
  return json(status, { title: code, status, code, ...extra });
}

const login = { username: 'ivanov', password: 'secret' };

describe('createApiClient', () => {
  it('sends reads without a CSRF header and without fetching a token', async () => {
    const server = fakeServer(() => json(200, { application: 'dispatch' }));

    const info = await unwrap(server.client.GET('/api/system/info'));

    expect(info).toEqual({ application: 'dispatch' });
    expect(server.calls).toEqual([{ method: 'GET', path: '/api/system/info', csrf: null }]);
  });

  it('puts the current cookie value into the CSRF header of a mutating call', async () => {
    const server = fakeServer(() => json(200, {}));
    server.setCookies('XSRF-TOKEN=abc%3D');

    await server.client.POST('/api/auth/login', { body: login });

    expect(server.calls).toEqual([{ method: 'POST', path: '/api/auth/login', csrf: 'abc=' }]);
  });

  it('fetches a token first when there is no CSRF cookie', async () => {
    const server = fakeServer(() => json(200, {}));

    await server.client.POST('/api/auth/login', { body: login });

    expect(server.calls.map((c) => [c.path, c.csrf])).toEqual([
      ['/api/auth/csrf', null],
      ['/api/auth/login', 'token-1'],
    ]);
  });

  it('refreshes the token and repeats once on 403 CSRF_INVALID', async () => {
    const server = fakeServer((call) => (call.csrf === 'stale' ? problem(403, 'CSRF_INVALID') : json(200, {})));
    server.setCookies('XSRF-TOKEN=stale');

    const { response } = await server.client.POST('/api/auth/login', { body: login });

    expect(response.status).toBe(200);
    expect(server.calls.map((c) => [c.path, c.csrf])).toEqual([
      ['/api/auth/login', 'stale'],
      ['/api/auth/csrf', null],
      ['/api/auth/login', 'token-2'],
    ]);
  });

  it('does not repeat a real denial (403 FORBIDDEN)', async () => {
    const server = fakeServer(() => problem(403, 'FORBIDDEN'));
    server.setCookies('XSRF-TOKEN=t');

    const error = await unwrap(server.client.POST('/api/auth/login', { body: login })).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 403, code: 'FORBIDDEN' });
    expect(server.calls).toHaveLength(1);
  });

  it('repeats a CSRF rejection only once', async () => {
    const server = fakeServer(() => problem(403, 'CSRF_INVALID'));
    server.setCookies('XSRF-TOKEN=t');

    const error = await unwrap(server.client.POST('/api/auth/login', { body: login })).catch((e: unknown) => e);

    expect(error).toMatchObject({ code: 'CSRF_INVALID' });
    expect(server.calls.filter((c) => c.path === '/api/auth/login')).toHaveLength(2);
  });

  it('turns a broken connection into NetworkError', async () => {
    const client = createApiClient({
      baseUrl: BASE,
      fetch: () => Promise.reject(new TypeError('Failed to fetch')),
      readCookies: () => '',
    });

    await expect(client.GET('/api/system/info')).rejects.toBeInstanceOf(NetworkError);
  });
});

describe('unwrap', () => {
  it('exposes field errors and the current card of a problem', async () => {
    const server = fakeServer(() =>
      problem(409, 'VERSION_CONFLICT', {
        errors: [{ field: 'comment', message: 'too long' }],
        current: { id: 'r1', version: 3 },
      }),
    );
    server.setCookies('XSRF-TOKEN=t');

    const error = await unwrap(server.client.POST('/api/auth/login', { body: login })).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    const apiError = error as ApiError;
    expect(apiError.fieldErrors).toEqual([{ field: 'comment', message: 'too long' }]);
    expect(apiError.current).toEqual({ id: 'r1', version: 3 });
  });

  it('keeps the status when the error body is not Problem Details', async () => {
    const server = fakeServer(() => new Response('<html>Bad Gateway</html>', { status: 502 }));

    const error = await unwrap(server.client.GET('/api/system/info')).catch((e: unknown) => e);

    expect(error).toMatchObject({ status: 502, code: 'HTTP_502', problem: undefined });
  });
});

describe('readCookie', () => {
  it('finds a cookie among others and decodes it', () => {
    expect(readCookie('a=1; XSRF-TOKEN=x%2By=; b=2', 'XSRF-TOKEN')).toBe('x+y=');
    expect(readCookie('a=1', 'XSRF-TOKEN')).toBeUndefined();
  });
});
