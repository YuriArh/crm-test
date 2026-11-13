/** A safe return path from `?next=`: only a local path, never another site. */
export function safeNext(next: string | null): string {
  if (next && next.startsWith('/') && !next.startsWith('//') && !next.startsWith('/login')) {
    return next;
  }
  return '/requests';
}
