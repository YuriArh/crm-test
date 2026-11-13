import type { FieldError, Problem, RequestCard } from './types';

/** An error answer of the API (Problem Details with the project's `code`). */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly problem: Problem | undefined;

  constructor(status: number, problem: Problem | undefined) {
    super(problem?.detail ?? problem?.title ?? `HTTP ${status}`);
    this.name = 'ApiError';
    this.status = status;
    this.problem = problem;
    this.code = problem?.code ?? `HTTP_${status}`;
  }

  /** Builds the error from whatever openapi-fetch parsed as the error body. */
  static from(status: number, body: unknown): ApiError {
    const problem = body && typeof body === 'object' && 'code' in body ? (body as Problem) : undefined;
    return new ApiError(status, problem);
  }

  get fieldErrors(): FieldError[] {
    return this.problem?.errors ?? [];
  }

  /** The current card sent with `VERSION_CONFLICT`, if any. */
  get current(): RequestCard | undefined {
    return this.problem?.current;
  }
}

/** The server could not be reached or the connection broke; the outcome is unknown. */
export class NetworkError extends Error {
  constructor(cause?: unknown) {
    super('Нет связи с сервером', { cause });
    this.name = 'NetworkError';
  }
}

export function isApiError(error: unknown, code?: string): error is ApiError {
  return error instanceof ApiError && (code === undefined || error.code === code);
}

/** Errors worth retrying automatically for reads and worth a global notification: network and 5xx. */
export function isTransient(error: unknown): boolean {
  return error instanceof NetworkError || (error instanceof ApiError && error.status >= 500);
}

/** A short Russian message for errors that have no special handling on the screen. */
export function describeError(error: unknown): string {
  if (error instanceof NetworkError) {
    return 'Нет связи с сервером. Проверьте подключение и повторите.';
  }
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'UNAUTHENTICATED':
        return 'Сессия истекла, войдите снова.';
      case 'FORBIDDEN':
        return 'Недостаточно прав для этого действия.';
      case 'NOT_FOUND':
        return 'Объект не найден.';
      case 'VALIDATION_FAILED':
        return 'Проверьте введённые данные.';
      case 'VERSION_CONFLICT':
        return 'Данные изменил другой пользователь. Обновите страницу.';
      default:
        return error.status >= 500
          ? 'Ошибка на сервере. Повторите попытку позже.'
          : `Ошибка: ${error.message}`;
    }
  }
  return 'Неизвестная ошибка.';
}
