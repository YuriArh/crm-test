import { useState } from 'react';

/**
 * `Idempotency-Key`s of user actions (NF04). A key belongs to one intent and lives while its outcome is unknown:
 * the same intent sent again after a lost answer or a `5xx` carries the same key, so dispatch replays the answer it
 * already gave instead of acting twice. Another intent (other content, another target) gets another key. Once the
 * outcome is known — any `2xx` or `4xx` — the caller forgets the key, and the next attempt is a new action.
 */
export type ActionKeys<T> = {
  /** The key of this intent: the one it already has, or a new one. */
  keyFor(intent: T): string;
  /** Drops the key of this intent (its outcome is known, or dispatch said `IDEMPOTENCY_KEY_REUSED`). */
  forget(intent: T): void;
};

/** `fingerprint` names an intent: equal fingerprints are one intent; list the fields, so their order never matters. */
export function createActionKeys<T>(
  fingerprint: (intent: T) => string,
  newKey: () => string = () => crypto.randomUUID(),
): ActionKeys<T> {
  const keys = new Map<string, string>();
  return {
    keyFor(intent) {
      const id = fingerprint(intent);
      let key = keys.get(id);
      if (key === undefined) {
        key = newKey();
        keys.set(id, key);
      }
      return key;
    },
    forget(intent) {
      keys.delete(fingerprint(intent));
    },
  };
}

/** Keys that live as long as the component that shows the action (a form, a dialog). */
export function useActionKeys<T>(fingerprint: (intent: T) => string): ActionKeys<T> {
  const [keys] = useState(() => createActionKeys(fingerprint));
  return keys;
}
