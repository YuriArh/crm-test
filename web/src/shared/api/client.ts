import createClient, { type Client } from 'openapi-fetch';
import { ApiError, NetworkError, isProblem } from './errors';
import type { paths } from './schema';

export const CSRF_COOKIE = 'XSRF-TOKEN';
export const CSRF_HEADER = 'X-XSRF-TOKEN';
const CSRF_PATH = '/api/auth/csrf';
const MUTATING = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

export type ApiClient = Client<paths>;

export type ApiClientOptions = {
  /** Origin of dispatch; the UI and the API share one origin. */
  baseUrl: string;
  fetch?: typeof globalThis.fetch;
  /** Returns the raw `document.cookie` string. */
  readCookies?: () => string;
};

/**
 * The single transport of the app. Every mutating call gets the CSRF header with the current value of
 * the `XSRF-TOKEN` cookie (read right before the call: the server rotates it on login and clears it
 * on logout). If there is no cookie, the token is fetched first.
 *
 * dispatch compares the header with the cookie, so a header read from the cookie passes. `403
 * CSRF_INVALID` therefore means the cookie went away while the call was in flight (logout in another
 * tab): the client asks for a token again and repeats the call once. `403 FORBIDDEN` is a real denial
 * and is not repeated. The repeat is safe: creates carry an Idempotency-Key and patches a version.
 * A broken connection becomes {@link NetworkError}; a failed token request becomes {@link ApiError}.
 */
export function createApiClient(options: ApiClientOptions): ApiClient {
  const baseFetch = options.fetch ?? ((input, init) => globalThis.fetch(input, init));
  const readCookies = options.readCookies ?? (() => document.cookie);

  async function send(request: Request): Promise<Response> {
    try {
      return await baseFetch(request);
    } catch (error) {
      throw new NetworkError(error);
    }
  }

  // A failure here (e.g. 502 while dispatch restarts) is reported as is, not as the CSRF_INVALID
  // the call would get without a token.
  async function refreshCsrf(): Promise<void> {
    const response = await send(new Request(new URL(CSRF_PATH, options.baseUrl), { credentials: 'same-origin' }));
    if (!response.ok) {
      throw ApiError.from(response.status, await response.json().catch(() => undefined));
    }
  }

  async function sendMutating(request: Request): Promise<Response> {
    let token = readCookie(readCookies(), CSRF_COOKIE);
    if (token === undefined) {
      await refreshCsrf();
      token = readCookie(readCookies(), CSRF_COOKIE);
    }
    if (token !== undefined) {
      request.headers.set(CSRF_HEADER, token);
    }
    return send(request);
  }

  async function apiFetch(request: Request): Promise<Response> {
    if (!MUTATING.has(request.method)) {
      return send(request);
    }
    const repeat = request.clone();
    const response = await sendMutating(request);
    if (!(await isCsrfRejection(response))) {
      return response;
    }
    await refreshCsrf();
    return sendMutating(repeat);
  }

  return createClient<paths>({
    baseUrl: options.baseUrl,
    credentials: 'same-origin',
    fetch: apiFetch,
  });
}

/** The client of the running app. */
export const api: ApiClient = createApiClient({ baseUrl: globalThis.location?.origin ?? 'http://localhost' });

/** Returns the data of a successful answer or throws {@link ApiError}. */
export async function unwrap<T>(call: Promise<{ data?: T; error?: unknown; response: Response }>): Promise<T> {
  const { data, error, response } = await call;
  if (!response.ok) {
    throw ApiError.from(response.status, error);
  }
  return data as T;
}

export function readCookie(cookies: string, name: string): string | undefined {
  for (const part of cookies.split(';')) {
    const [key, ...rest] = part.trim().split('=');
    if (key === name) {
      const value = rest.join('=');
      try {
        return decodeURIComponent(value);
      } catch {
        // Malformed percent-encoding: send the raw value and let the server judge it.
        return value;
      }
    }
  }
  return undefined;
}

async function isCsrfRejection(response: Response): Promise<boolean> {
  if (response.status !== 403) {
    return false;
  }
  try {
    const body: unknown = await response.clone().json();
    return isProblem(body) && body.code === 'CSRF_INVALID';
  } catch {
    return false;
  }
}
