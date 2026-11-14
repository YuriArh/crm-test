// The data layer: the only code that talks HTTP to dispatch. Modules build their queries and
// mutations on `api` + `unwrap` and handle failures through the error types.
export { api, createApiClient, unwrap, CSRF_COOKIE, CSRF_HEADER } from './client';
export type { ApiClient, ApiClientOptions } from './client';
export { ApiError, NetworkError, isApiError, isTransient } from './errors';
export type * from './types';
