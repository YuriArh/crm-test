import { useState } from 'react';
import type { CreateRequest } from '@/shared/api';

/**
 * `Idempotency-Key`s of one open create form (NF04). The key belongs to the content as it is sent:
 * the same content gets the same key, so a repeat after a lost answer makes dispatch replay the request
 * it already created instead of creating a second one; changed content is another action and gets a new
 * key. The keys live as long as the form: a new form starts with none.
 */
export type IdempotencyKeys = {
  /** The key for this body: the one it already had in this form, or a new one. */
  keyFor(body: CreateRequest): string;
  /** Drops the key of this body, so the next {@link keyFor} makes a new one (after `IDEMPOTENCY_KEY_REUSED`). */
  renew(body: CreateRequest): void;
};

/** The content that identifies a create action; the fields are listed so their order never matters. */
function fingerprint(body: CreateRequest): string {
  return JSON.stringify([body.cargoDescription, body.weightKg, body.deliveryAddress, body.deadline]);
}

export function createIdempotencyKeys(newKey: () => string = () => crypto.randomUUID()): IdempotencyKeys {
  const keys = new Map<string, string>();
  return {
    keyFor(body) {
      const id = fingerprint(body);
      let key = keys.get(id);
      if (key === undefined) {
        key = newKey();
        keys.set(id, key);
      }
      return key;
    },
    renew(body) {
      keys.delete(fingerprint(body));
    },
  };
}

/** The keys of the form being shown; they go away with it. */
export function useIdempotencyKeys(): IdempotencyKeys {
  const [keys] = useState(() => createIdempotencyKeys());
  return keys;
}
