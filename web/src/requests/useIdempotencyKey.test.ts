import { renderHook } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import type { CreateRequest } from '../api/types';
import { useIdempotencyKey } from './useIdempotencyKey';

const body: CreateRequest = {
  cargoDescription: 'Паллеты',
  weightKg: 12.5,
  deliveryAddress: 'ул. Ленина, 1',
  deadline: '2026-12-01',
};

describe('Idempotency-Key of the create form', () => {
  it('reuses the key for the same content and makes a new one when content changes', () => {
    const { result } = renderHook(() => useIdempotencyKey());
    const first = result.current(body);
    expect(result.current({ ...body })).toBe(first);

    const changed = result.current({ ...body, weightKg: 13 });
    expect(changed).not.toBe(first);
    expect(result.current({ ...body, weightKg: 13 })).toBe(changed);
  });
});
