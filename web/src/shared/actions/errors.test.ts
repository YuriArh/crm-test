import { describe, expect, it } from 'vitest';
import { ApiError, NetworkError, type Problem } from '@/shared/api';
import { describeActionFailure } from './errors';

const texts = { stale: 'Состояние изменилось', gone: 'Не найдено' };

function apiError(status: number, code: string, extra: Partial<Problem> = {}) {
  return new ApiError(status, { title: code, status, code, ...extra } as Problem);
}

describe('describeActionFailure', () => {
  it('calls a lost connection or a server failure an unknown outcome a repeat may cure safely', () => {
    for (const error of [new NetworkError(), apiError(503, 'HTTP_503'), apiError(500, 'INTERNAL')]) {
      expect(describeActionFailure(error, texts)).toEqual({
        kind: 'transient',
        message: 'Не удалось связаться с сервером. Повторите — повтор безопасен',
      });
    }
  });

  it('calls 409 a stale screen', () => {
    expect(describeActionFailure(apiError(409, 'INVALID_STATE'), texts)).toEqual({ kind: 'stale', message: texts.stale });
  });

  it('calls 404 gone', () => {
    expect(describeActionFailure(apiError(404, 'NOT_FOUND'), texts)).toEqual({ kind: 'gone', message: texts.gone });
  });

  it('explains 403', () => {
    expect(describeActionFailure(apiError(403, 'FORBIDDEN'), texts)).toEqual({
      kind: 'forbidden',
      message: 'Действие доступно только оператору службы заявки',
    });
  });

  it('asks to repeat after a reused key (the caller makes a new one)', () => {
    expect(describeActionFailure(apiError(422, 'IDEMPOTENCY_KEY_REUSED'), texts)).toEqual({
      kind: 'reused',
      message: 'Повторите действие',
    });
  });

  it('hands field errors to the form', () => {
    const errors = [{ field: 'comment', message: 'must not be blank' }];
    expect(describeActionFailure(apiError(400, 'VALIDATION_FAILED', { errors }), texts)).toEqual({
      kind: 'invalid',
      message: 'Проверьте поля',
      fieldErrors: errors,
    });
  });

  it('calls any other client error a fault of this client', () => {
    for (const error of [apiError(400, 'IDEMPOTENCY_KEY_REQUIRED'), apiError(400, 'IDEMPOTENCY_KEY_TOO_LONG'), apiError(418, 'X')]) {
      expect(describeActionFailure(error, texts)).toEqual({
        kind: 'client',
        message: 'Не удалось выполнить действие. Обновите страницу',
      });
    }
  });

  it('leaves 401 to the app, which signs the user in again', () => {
    expect(describeActionFailure(apiError(401, 'UNAUTHENTICATED'), texts)).toEqual({ kind: 'session' });
  });
});
