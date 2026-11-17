import { isApiError, isTransient, type FieldError } from '@/shared/api';

/**
 * What a failed user action means for the screen (web-problems.md §8.5):
 * - `transient` — no connection or a server failure: the outcome is unknown, a repeat with the same key is safe;
 * - `stale` — `409`: the screen is out of date; ask the server again and say so;
 * - `gone` — `404`: the target left the user's scope;
 * - `reused` — `422 IDEMPOTENCY_KEY_REUSED`: make a new key and repeat;
 * - `invalid` — `400 VALIDATION_FAILED`: field errors for the form;
 * - `forbidden` — `403`; `client` — any other client error, a fault of this client;
 * - `session` — `401`: the app signs the user in again, the action shows nothing.
 */
export type ActionFailure =
  | { kind: 'transient' | 'stale' | 'gone' | 'reused' | 'forbidden' | 'client'; message: string }
  | { kind: 'invalid'; message: string; fieldErrors: FieldError[] }
  | { kind: 'session' };

/** The words that depend on the action: what changed (`409`) and what is gone (`404`). */
export type ActionTexts = { stale: string; gone: string };

export function describeActionFailure(error: unknown, texts: ActionTexts): ActionFailure {
  if (isTransient(error)) {
    return { kind: 'transient', message: 'Не удалось связаться с сервером. Повторите — повтор безопасен' };
  }
  if (!isApiError(error)) return { kind: 'client', message: 'Не удалось выполнить действие. Обновите страницу' };
  if (error.status === 401) return { kind: 'session' };
  if (error.status === 409) return { kind: 'stale', message: texts.stale };
  if (error.status === 404) return { kind: 'gone', message: texts.gone };
  if (error.status === 403) return { kind: 'forbidden', message: 'Действие доступно только оператору службы заявки' };
  if (error.code === 'IDEMPOTENCY_KEY_REUSED') return { kind: 'reused', message: 'Повторите действие' };
  if (error.code === 'VALIDATION_FAILED') {
    return { kind: 'invalid', message: 'Проверьте поля', fieldErrors: error.fieldErrors };
  }
  return { kind: 'client', message: 'Не удалось выполнить действие. Обновите страницу' };
}
