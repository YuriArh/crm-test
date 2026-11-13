import type { BusinessStatus, DeliveryStatus } from '../api/types';
import { businessStatuses, deliveryStatuses } from './statuses';

export const sortOptions = [
  { value: 'createdAt,desc', label: 'Сначала новые' },
  { value: 'createdAt,asc', label: 'Сначала старые' },
  { value: 'deadline,asc', label: 'Срок: ближайшие' },
  { value: 'deadline,desc', label: 'Срок: дальние' },
  { value: 'number,asc', label: 'Номер по возрастанию' },
  { value: 'number,desc', label: 'Номер по убыванию' },
] as const;

export const DEFAULT_SORT = 'createdAt,desc';
export const PAGE_SIZE = 20;

/** Registry state kept in the URL, so a link reproduces the view. `page` is 1-based here. */
export interface ListParams {
  businessStatus?: BusinessStatus;
  deliveryStatus?: DeliveryStatus;
  overdue?: boolean;
  page: number;
  sort: string;
}

function oneOf<T extends string>(value: string | null, allowed: Record<T, unknown>): T | undefined {
  return value !== null && value in allowed ? (value as T) : undefined;
}

export function parseListParams(search: URLSearchParams): ListParams {
  const page = Number.parseInt(search.get('page') ?? '', 10);
  const sort = search.get('sort');
  const overdue = search.get('overdue');
  return {
    businessStatus: oneOf(search.get('businessStatus'), businessStatuses),
    deliveryStatus: oneOf(search.get('deliveryStatus'), deliveryStatuses),
    overdue: overdue === 'true' ? true : overdue === 'false' ? false : undefined,
    page: Number.isFinite(page) && page > 0 ? page : 1,
    sort: sortOptions.some((option) => option.value === sort) ? (sort as string) : DEFAULT_SORT,
  };
}

export function toSearch(params: ListParams): URLSearchParams {
  const search = new URLSearchParams();
  if (params.businessStatus) search.set('businessStatus', params.businessStatus);
  if (params.deliveryStatus) search.set('deliveryStatus', params.deliveryStatus);
  if (params.overdue !== undefined) search.set('overdue', String(params.overdue));
  if (params.page > 1) search.set('page', String(params.page));
  if (params.sort !== DEFAULT_SORT) search.set('sort', params.sort);
  return search;
}

export function hasFilters(params: ListParams): boolean {
  return (
    params.businessStatus !== undefined || params.deliveryStatus !== undefined || params.overdue !== undefined
  );
}
