import { describe, expect, it } from 'vitest';
import { ApiError, NetworkError, isApiError, isTransient } from './errors';

describe('isTransient', () => {
  it('is true for a broken connection and server failures only', () => {
    expect(isTransient(new NetworkError())).toBe(true);
    expect(isTransient(new ApiError(500, undefined))).toBe(true);
    expect(isTransient(new ApiError(503, undefined))).toBe(true);
    expect(isTransient(new ApiError(404, undefined))).toBe(false);
    expect(isTransient(new ApiError(409, undefined))).toBe(false);
    expect(isTransient(new Error('bug'))).toBe(false);
  });
});

describe('isApiError', () => {
  it('matches by code when one is given', () => {
    const error = ApiError.from(401, { title: 'Unauthorized', status: 401, code: 'UNAUTHENTICATED' });

    expect(isApiError(error)).toBe(true);
    expect(isApiError(error, 'UNAUTHENTICATED')).toBe(true);
    expect(isApiError(error, 'FORBIDDEN')).toBe(false);
    expect(isApiError(new NetworkError())).toBe(false);
  });

  it('uses the problem detail as the message', () => {
    const error = ApiError.from(404, { title: 'Not Found', status: 404, code: 'NOT_FOUND', detail: 'No request r1' });

    expect(error.message).toBe('No request r1');
  });
});
