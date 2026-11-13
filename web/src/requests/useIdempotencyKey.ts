import { useRef } from 'react';

import type { CreateRequest } from '../api/types';
import { randomUuid } from './uuid';

/**
 * One Idempotency-Key per content: a repeat of the same content (double click, retry after a lost
 * answer) reuses the key, so the server creates at most one request; changed content gets a new key.
 */
export function useIdempotencyKey() {
  const attempt = useRef<{ key: string; payload: string } | null>(null);
  return (body: CreateRequest): string => {
    const payload = JSON.stringify(body);
    if (attempt.current?.payload !== payload) {
      attempt.current = { key: randomUuid(), payload };
    }
    return attempt.current.key;
  };
}
