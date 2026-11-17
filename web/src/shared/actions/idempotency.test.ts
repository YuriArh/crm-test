import { describe, expect, it } from 'vitest';
import { createActionKeys } from './idempotency';

type Decision = { problemId: string; decision: string; comment: string };

const decision: Decision = { problemId: 'p-1', decision: 'KEEP_OURS', comment: 'Проверено' };

function sequentialKeys() {
  let n = 0;
  return createActionKeys<Decision>((d) => JSON.stringify([d.problemId, d.decision, d.comment]), () => `key-${++n}`);
}

describe('createActionKeys', () => {
  it('gives one intent the same key, so a repeat after an unknown outcome is a replay', () => {
    const keys = sequentialKeys();
    expect(keys.keyFor(decision)).toBe('key-1');
    expect(keys.keyFor({ ...decision })).toBe('key-1');
  });

  it('gives another intent another key', () => {
    const keys = sequentialKeys();
    keys.keyFor(decision);
    expect(keys.keyFor({ ...decision, comment: 'Другое основание' })).toBe('key-2');
    expect(keys.keyFor({ ...decision, decision: 'APPLY_CARRIER' })).toBe('key-3');
    expect(keys.keyFor({ ...decision, problemId: 'p-2' })).toBe('key-4');
  });

  it('forgets the key of an intent whose outcome is known, so the next attempt is a new action', () => {
    const keys = sequentialKeys();
    keys.keyFor(decision);
    keys.keyFor({ ...decision, comment: 'Другое' });
    keys.forget(decision);
    expect(keys.keyFor(decision)).toBe('key-3');
    expect(keys.keyFor({ ...decision, comment: 'Другое' })).toBe('key-2');
  });

  it('keeps the keys of two sets apart', () => {
    const fingerprint = (d: Decision) => d.problemId;
    expect(createActionKeys(fingerprint).keyFor(decision)).not.toBe(createActionKeys(fingerprint).keyFor(decision));
  });

  it('uses UUIDs by default', () => {
    expect(createActionKeys((d: Decision) => d.problemId).keyFor(decision)).toMatch(/^[0-9a-f-]{36}$/);
  });
});
