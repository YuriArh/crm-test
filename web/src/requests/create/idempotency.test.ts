import { describe, expect, it } from 'vitest';
import type { CreateRequest } from '@/shared/api';
import { createIdempotencyKeys } from './idempotency';

const body: CreateRequest = {
  cargoDescription: 'Паллеты',
  weightKg: 12.5,
  deliveryAddress: 'ул. Ленина, 1',
  deadline: '2026-12-01',
};

function sequentialKeys() {
  let n = 0;
  return createIdempotencyKeys(() => `key-${++n}`);
}

describe('createIdempotencyKeys', () => {
  it('gives the same content the same key, so a repeat after a lost answer is a replay', () => {
    const keys = sequentialKeys();
    expect(keys.keyFor(body)).toBe('key-1');
    expect(keys.keyFor({ ...body })).toBe('key-1');
  });

  it('gives changed content a new key', () => {
    const keys = sequentialKeys();
    keys.keyFor(body);
    expect(keys.keyFor({ ...body, weightKg: 12.6 })).toBe('key-2');
    expect(keys.keyFor({ ...body, cargoDescription: 'Коробки' })).toBe('key-3');
    expect(keys.keyFor({ ...body, deliveryAddress: 'ул. Мира, 2' })).toBe('key-4');
    expect(keys.keyFor({ ...body, deadline: '2026-12-02' })).toBe('key-5');
  });

  it('keeps the key of content the user changed and then changed back', () => {
    const keys = sequentialKeys();
    keys.keyFor(body);
    keys.keyFor({ ...body, weightKg: 13 });
    expect(keys.keyFor(body)).toBe('key-1');
  });

  it('makes a new key for the same content after renew', () => {
    const keys = sequentialKeys();
    keys.keyFor(body);
    keys.renew(body);
    expect(keys.keyFor(body)).toBe('key-2');
  });

  it('starts every form with its own keys', () => {
    expect(createIdempotencyKeys().keyFor(body)).not.toBe(createIdempotencyKeys().keyFor(body));
  });

  it('uses UUIDs by default', () => {
    expect(createIdempotencyKeys().keyFor(body)).toMatch(/^[0-9a-f-]{36}$/);
  });
});
