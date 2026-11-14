export const HOME_PATH = '/';

/**
 * A safe return path from `?next=`: only a path on this site, never another site (`//evil`,
 * `https://…`) and never the login page itself. Anything else returns home.
 */
export function safeNext(next: unknown): string {
  if (typeof next === 'string' && next.startsWith('/') && !next.startsWith('//') && !next.startsWith('/\\')) {
    const path = next.split(/[?#]/, 1)[0];
    if (path !== '/login' && !path?.startsWith('/login/')) {
      return next;
    }
  }
  return HOME_PATH;
}
