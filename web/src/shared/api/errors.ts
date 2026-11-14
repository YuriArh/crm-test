import type { FieldError, Problem, RequestCard } from './types';

/** An error answer of the API: Problem Details with the project's machine-readable `code`. */
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

  /** Builds the error from whatever was parsed as the error body (not always Problem Details, e.g. a proxy page). */
  static from(status: number, body: unknown): ApiError {
    return new ApiError(status, isProblem(body) ? body : undefined);
  }

  /** Field errors of `VALIDATION_FAILED`. */
  get fieldErrors(): FieldError[] {
    return this.problem?.errors ?? [];
  }

  /** The current card sent with `VERSION_CONFLICT`, if any. */
  get current(): RequestCard | undefined {
    return this.problem?.current;
  }
}

/** The server could not be reached or the connection broke: the outcome of the call is unknown. */
export class NetworkError extends Error {
  constructor(cause?: unknown) {
    super('Network error', { cause });
    this.name = 'NetworkError';
  }
}

export function isProblem(body: unknown): body is Problem {
  return typeof body === 'object' && body !== null && typeof (body as { code?: unknown }).code === 'string';
}

export function isApiError(error: unknown, code?: string): error is ApiError {
  return error instanceof ApiError && (code === undefined || error.code === code);
}

/** Errors a repeat may cure: no connection or a server failure. Client errors (4xx) never are. */
export function isTransient(error: unknown): boolean {
  return error instanceof NetworkError || (error instanceof ApiError && error.status >= 500);
}
