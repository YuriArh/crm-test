import type { ProblemKind } from '@/shared/api';
import { KIND_VIEWS } from '../labels';

/**
 * The state of the problems list lives in the address (`/problems?status=all&kind=[…]&page=2`), by the rules of
 * the registry: defaults are never written, junk is dropped, a filter change goes back to page 1.
 */
export const PROBLEM_PAGE_SIZE = 20;

export type StatusFilter = 'open' | 'resolved' | 'all';
const STATUS_FILTERS: readonly StatusFilter[] = ['open', 'resolved', 'all'];
const API_STATUS = { open: 'OPEN', resolved: 'RESOLVED', all: 'ALL' } as const;

/** The kinds in the order the filter lists them. */
export const PROBLEM_KINDS = Object.keys(KIND_VIEWS) as ProblemKind[];

export type ProblemListSearch = {
  /** `open` is the default and never written. */
  status?: Exclude<StatusFilter, 'open'>;
  /** Several kinds; none means every kind. */
  kind?: ProblemKind[];
  /** From 1; 1 is never written. */
  page?: number;
};

export type ProblemListParams = {
  status: (typeof API_STATUS)[StatusFilter];
  kind?: ProblemKind[];
  page: number;
  size: number;
};

function positiveInt(value: unknown): number | undefined {
  const number = typeof value === 'string' && /^\d+$/.test(value) ? Number(value) : value;
  return typeof number === 'number' && Number.isSafeInteger(number) && number >= 1 ? number : undefined;
}

/** `validateSearch` of the list: a broken link opens the list instead of failing. */
export function parseProblemSearch(raw: Record<string, unknown>): ProblemListSearch {
  const search: ProblemListSearch = {};
  const status = STATUS_FILTERS.find((value) => value === raw.status);
  if (status && status !== 'open') search.status = status;
  const given = Array.isArray(raw.kind) ? raw.kind : [raw.kind];
  const kinds = PROBLEM_KINDS.filter((kind) => given.includes(kind));
  if (kinds.length > 0) search.kind = kinds;
  const page = positiveInt(raw.page);
  if (page && page > 1) search.page = page;
  return search;
}

export function statusOf(search: ProblemListSearch): StatusFilter {
  return search.status ?? 'open';
}

/** The address → query of `GET /api/problems` (pages from 0 there). */
export function toProblemApiParams(search: ProblemListSearch): ProblemListParams {
  const clean = parseProblemSearch(search);
  const params: ProblemListParams = {
    status: API_STATUS[statusOf(clean)],
    page: (clean.page ?? 1) - 1,
    size: PROBLEM_PAGE_SIZE,
  };
  if (clean.kind) params.kind = clean.kind;
  return params;
}

export function withStatus(search: ProblemListSearch, status: StatusFilter): ProblemListSearch {
  return parseProblemSearch({ ...search, status, page: undefined });
}

export function withKinds(search: ProblemListSearch, kind: ProblemKind[]): ProblemListSearch {
  return parseProblemSearch({ ...search, kind, page: undefined });
}

export function withPage(search: ProblemListSearch, page: number): ProblemListSearch {
  return parseProblemSearch({ ...search, page });
}

export function withoutFilters(): ProblemListSearch {
  return {};
}

/** How many filters narrow the list (the phone's «Фильтры · N»). */
export function activeFilterCount(search: ProblemListSearch): number {
  return (search.status ? 1 : 0) + (search.kind ? 1 : 0);
}
