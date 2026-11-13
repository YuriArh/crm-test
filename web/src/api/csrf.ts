import { NetworkError } from './errors';

export const CSRF_COOKIE = 'XSRF-TOKEN';
export const CSRF_HEADER = 'X-XSRF-TOKEN';

/** The current CSRF token. Read right before every mutating call: the server rotates it on login. */
export function readCsrfCookie(): string | undefined {
  for (const part of document.cookie.split(';')) {
    const [name, ...rest] = part.trim().split('=');
    if (name === CSRF_COOKIE) {
      return decodeURIComponent(rest.join('='));
    }
  }
  return undefined;
}

/** Asks the server to (re)issue the `XSRF-TOKEN` cookie. A broken connection is a {@link NetworkError}. */
export async function refreshCsrf(): Promise<void> {
  try {
    await fetch(new URL('/api/auth/csrf', window.location.origin), { credentials: 'same-origin' });
  } catch (error) {
    throw new NetworkError(error);
  }
}
