import createClient from 'openapi-fetch';

import { CSRF_HEADER, readCsrfCookie, refreshCsrf } from './csrf';
import { ApiError, NetworkError } from './errors';
import type { paths } from './schema';

const MUTATING = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

/**
 * The single transport of the app. Adds the CSRF header to mutating calls; on `403 CSRF_INVALID` fetches
 * a fresh token and repeats the call once (the token may be stale, e.g. after logout in another tab).
 * A plain `403 FORBIDDEN` is a real denial and is not repeated. Repeating is safe: creates carry an
 * Idempotency-Key and patches a version.
 */
async function apiFetch(request: Request): Promise<Response> {
  if (!MUTATING.has(request.method)) {
    return send(request);
  }
  const retry = request.clone();
  const response = await sendMutating(request);
  if (!(await isCsrfRejection(response))) {
    return response;
  }
  await refreshCsrf();
  return sendMutating(retry);
}

async function isCsrfRejection(response: Response): Promise<boolean> {
  if (response.status !== 403) {
    return false;
  }
  try {
    const body: unknown = await response.clone().json();
    return typeof body === 'object' && body !== null && (body as { code?: unknown }).code === 'CSRF_INVALID';
  } catch {
    return false;
  }
}

async function sendMutating(request: Request): Promise<Response> {
  let token = readCsrfCookie();
  if (token === undefined) {
    await refreshCsrf();
    token = readCsrfCookie();
  }
  if (token !== undefined) {
    request.headers.set(CSRF_HEADER, token);
  }
  return send(request);
}

async function send(request: Request): Promise<Response> {
  try {
    return await fetch(request);
  } catch (error) {
    throw new NetworkError(error);
  }
}

export const api = createClient<paths>({
  baseUrl: window.location.origin,
  credentials: 'same-origin',
  fetch: apiFetch,
});

/** Returns the data of a successful answer or throws {@link ApiError}. */
export async function unwrap<T>(
  call: Promise<{ data?: T; error?: unknown; response: Response }>,
): Promise<T> {
  const { data, error, response } = await call;
  if (!response.ok) {
    throw ApiError.from(response.status, error);
  }
  return data as T;
}
