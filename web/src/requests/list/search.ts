import type { BusinessStatus, DeliveryStatus, Role } from '@/shared/api';

/**
 * The state of the requests list lives in the address (`/requests?result=…&delivery=…&overdue=1&sort=…&page=…`),
 * so a link can be shared and Back returns the previous filter. Defaults are never written to the address:
 * a clean `/requests` is all requests, newest first, page 1.
 */

export const PAGE_SIZE = 20;

export const BUSINESS_STATUSES: readonly BusinessStatus[] = ['AWAITING', 'IN_PROGRESS', 'SUCCEEDED', 'REJECTED'];
export const DELIVERY_STATUSES: readonly DeliveryStatus[] = [
  'PENDING',
  'SENDING',
  'RETRY_WAIT',
  'UNKNOWN',
  'ACKNOWLEDGED',
  'FAILED',
];

export type ListSort = 'new' | 'old' | 'deadline' | 'number';
export const DEFAULT_SORT: ListSort = 'new';

export const SORT_OPTIONS: readonly { value: ListSort; label: string }[] = [
  { value: 'new', label: 'Сначала новые' },
  { value: 'old', label: 'Сначала старые' },
  { value: 'deadline', label: 'По сроку' },
  { value: 'number', label: 'По номеру' },
];

const API_SORT: Record<ListSort, string> = {
  new: 'createdAt,desc',
  old: 'createdAt,asc',
  deadline: 'deadline,asc',
  number: 'number,desc',
};

/** The address of the list; every field is optional, a missing field means its default. */
export type RequestListSearch = {
  result?: BusinessStatus;
  delivery?: DeliveryStatus;
  /** `overdue=1`: only overdue requests. */
  overdue?: 1;
  sort?: Exclude<ListSort, 'new'>;
  /** From 1; 1 is never written. */
  page?: number;
};

/** The filters a user can change (everything but the page). */
export type ListFilters = Pick<RequestListSearch, 'result' | 'delivery' | 'overdue'>;

/** Query parameters of `GET /api/requests`. */
export type RequestListParams = {
  businessStatus?: BusinessStatus;
  deliveryStatus?: DeliveryStatus;
  overdue?: true;
  page: number;
  size: number;
  sort: string;
};

function oneOf<T extends string>(values: readonly T[], value: unknown): T | undefined {
  return typeof value === 'string' && (values as readonly string[]).includes(value) ? (value as T) : undefined;
}

function positiveInt(value: unknown): number | undefined {
  const number = typeof value === 'string' && /^\d+$/.test(value) ? Number(value) : value;
  return typeof number === 'number' && Number.isSafeInteger(number) && number >= 1 ? number : undefined;
}

/**
 * Parses the address (`validateSearch` of the route). Unknown values and defaults are dropped, so a broken
 * link opens the list instead of failing. Values arrive already JSON-parsed by the router (`page=2` → 2).
 */
export function parseListSearch(raw: Record<string, unknown>): RequestListSearch {
  const search: RequestListSearch = {};
  const result = oneOf(BUSINESS_STATUSES, raw.result);
  if (result) search.result = result;
  const delivery = oneOf(DELIVERY_STATUSES, raw.delivery);
  if (delivery) search.delivery = delivery;
  if (raw.overdue === 1 || raw.overdue === '1' || raw.overdue === true || raw.overdue === 'true') search.overdue = 1;
  const sort = oneOf<ListSort>(SORT_OPTIONS.map((option) => option.value), raw.sort);
  if (sort !== undefined && sort !== 'new') search.sort = sort;
  const page = positiveInt(raw.page);
  if (page && page > 1) search.page = page;
  return search;
}

/** The address as this user may use it: initiators have no delivery filter (their delivery status is simplified). */
export function searchForRole(search: RequestListSearch, role: Role): RequestListSearch {
  if (role === 'OPERATOR' || search.delivery === undefined) return search;
  const { delivery: _dropped, ...rest } = search;
  return rest;
}

/** The address → query parameters of `GET /api/requests` (pages from 0 there). */
export function toApiParams(search: RequestListSearch, role: Role): RequestListParams {
  const effective = searchForRole(search, role);
  const params: RequestListParams = {
    page: (effective.page ?? 1) - 1,
    size: PAGE_SIZE,
    sort: API_SORT[effective.sort ?? DEFAULT_SORT],
  };
  if (effective.result) params.businessStatus = effective.result;
  if (effective.delivery) params.deliveryStatus = effective.delivery;
  if (effective.overdue) params.overdue = true;
  return params;
}

/** Changes filters and goes back to the first page; `undefined` clears a filter. */
export function withFilters(search: RequestListSearch, change: Partial<ListFilters>): RequestListSearch {
  return parseListSearch({ ...search, ...change, page: undefined });
}

/** Changes the sort and goes back to the first page. */
export function withSort(search: RequestListSearch, sort: ListSort): RequestListSearch {
  return parseListSearch({ ...search, sort, page: undefined });
}

/** Goes to `page` (from 1), keeping filters and sort. */
export function withPage(search: RequestListSearch, page: number): RequestListSearch {
  return parseListSearch({ ...search, page });
}

/** Clears every filter, keeping the sort; back to the first page. */
export function withoutFilters(search: RequestListSearch): RequestListSearch {
  return parseListSearch({ sort: search.sort });
}

/** `1–20 из 128` for page 0 of 128 rows; `0 из 0` for an empty list. */
export function formatPageRange(page: number, size: number, total: number): string {
  if (total === 0) return '0 из 0';
  const from = page * size + 1;
  const to = Math.min(total, (page + 1) * size);
  return `${from}–${to} из ${total}`;
}

/** How many filters narrow the list (for the "Фильтры · N" button and "Сбросить"). */
export function activeFilterCount(search: RequestListSearch, role: Role): number {
  const effective = searchForRole(search, role);
  return [effective.result, effective.delivery, effective.overdue].filter((value) => value !== undefined).length;
}
