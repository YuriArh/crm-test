import { Alert, Box, Button, Group, Pagination, Progress, Stack, Text, Title } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { getRouteApi } from '@tanstack/react-router';
import { useEffect } from 'react';
import { useCurrentUser } from '@/auth';
import { isApiError, NetworkError, type Role } from '@/shared/api';
import { useBusinessTimezone } from '@/shared/system';
import { requestListQueryOptions } from './api';
import classes from './RequestList.module.css';
import { RequestFilterBar } from './RequestFilters';
import { RequestTable, RequestTableSkeleton } from './RequestTable';
import {
  activeFilterCount,
  formatPageRange,
  parseListSearch,
  searchForRole,
  toApiParams,
  withFilters,
  withoutFilters,
  withPage,
  withSort,
  type ListFilters,
  type ListSort,
} from './search';

const route = getRouteApi('/_authenticated/requests');

function loadErrorText(error: Error): string {
  return error instanceof NetworkError
    ? 'Нет связи с сервером. Проверьте подключение.'
    : 'Не удалось загрузить заявки. Повторите попытку позже.';
}

function EmptyMessage({ role }: { role: Role }) {
  return (
    <Stack align="center" gap="xs" py="xl">
      <Text fw={510}>{role === 'OPERATOR' ? 'В службе пока нет заявок' : 'Заявок пока нет'}</Text>
    </Stack>
  );
}

/** The registry: filters and sort from the address, a table on desktops, pages of 20. */
export function RequestListPage() {
  const me = useCurrentUser();
  const timeZone = useBusinessTimezone();
  // The router keeps address keys that validation dropped; parse again so only clean values are used.
  const rawSearch = route.useSearch();
  const search = searchForRole(parseListSearch(rawSearch), me.role);
  const navigate = route.useNavigate();
  const params = toApiParams(search, me.role);
  const query = useQuery(requestListQueryOptions(params));
  const { data, error } = query;
  const activeCount = activeFilterCount(search, me.role);
  const filtered = activeCount > 0;
  const clean = (prev: Record<string, unknown>) => searchForRole(parseListSearch(prev), me.role);
  const changeFilters = (change: Partial<ListFilters>) => void navigate({ search: (prev) => withFilters(clean(prev), change) });
  const resetFilters = () => void navigate({ search: (prev) => withoutFilters(clean(prev)) });
  const changeSort = (sort: ListSort) => void navigate({ search: (prev) => withSort(clean(prev), sort) });

  // Garbage in the address (`result=foo`, `page=abc`, an initiator's `delivery`) is dropped from it as well.
  const dirty = JSON.stringify(rawSearch) !== JSON.stringify(search);
  useEffect(() => {
    if (dirty) void navigate({ search: (prev) => searchForRole(parseListSearch(prev), me.role), replace: true });
  }, [dirty, navigate, me.role]);

  // A broken address that still reached the API (400): back to the defaults.
  const badRequest = isApiError(error) && error.status === 400;
  const hasSearch = Object.keys(search).length > 0;
  useEffect(() => {
    if (badRequest && hasSearch) void navigate({ search: {}, replace: true });
  }, [badRequest, hasSearch, navigate]);

  // There are fewer requests than the address expects: go to the last page that exists.
  const page = search.page ?? 1;
  const lastPage = data && !query.isPlaceholderData ? Math.max(1, data.totalPages) : undefined;
  useEffect(() => {
    if (lastPage !== undefined && page > lastPage) void navigate({ search: (prev) => withPage(prev, lastPage), replace: true });
  }, [lastPage, page, navigate]);

  const loadingMore = query.isFetching && query.isPlaceholderData;

  return (
    <Stack gap="md">
      <Group gap="sm" align="baseline">
        <Title order={2}>Заявки</Title>
        {data && (
          <Text c="dimmed" size="lg" data-testid="request-total">
            {data.totalElements}
          </Text>
        )}
      </Group>

      <RequestFilterBar
        search={search}
        role={me.role}
        activeCount={activeCount}
        onFilters={changeFilters}
        onReset={resetFilters}
        onSort={changeSort}
      />

      <Box>
        <Progress
          value={100}
          animated
          size="xs"
          radius={0}
          className={classes.loadingBar}
          data-active={loadingMore || undefined}
          aria-hidden
        />
        {error && data && (
          <Alert color="yellow" mb="sm" title="Список мог устареть">
            <Group justify="space-between" gap="sm">
              <Text size="md">{loadErrorText(error)}</Text>
              <Button size="xs" variant="default" onClick={() => void query.refetch()}>
                Повторить
              </Button>
            </Group>
          </Alert>
        )}

        {!data && error && !badRequest && (
          <Stack align="center" gap="sm" py="xl" role="alert">
            <Text>{loadErrorText(error)}</Text>
            <Button variant="default" onClick={() => void query.refetch()}>
              Повторить
            </Button>
          </Stack>
        )}

        {!data && !error && <RequestTableSkeleton role={me.role} />}

        {data && data.totalElements === 0 && !filtered && <EmptyMessage role={me.role} />}
        {data && data.totalElements === 0 && filtered && (
          <Stack align="center" gap="sm" py="xl">
            <Text fw={510}>Ничего не найдено</Text>
            <Button variant="default" onClick={resetFilters}>
              Сбросить фильтры
            </Button>
          </Stack>
        )}
        {data && data.items.length > 0 && <RequestTable items={data.items} role={me.role} timeZone={timeZone} />}
      </Box>

      {data && data.totalElements > 0 && (
        <Group justify="space-between" gap="sm">
          <Text size="md" c="dimmed">
            {formatPageRange(data.page, data.size, data.totalElements)}
          </Text>
          <Pagination
            total={data.totalPages}
            value={Math.min(page, Math.max(1, data.totalPages))}
            onChange={(next) => void navigate({ search: (prev) => withPage(prev, next) })}
            size="sm"
            hideWithOnePage
            getControlProps={(control) => ({
              'aria-label': control === 'next' ? 'Следующая страница' : 'Предыдущая страница',
            })}
            getItemProps={(item) => ({ 'aria-label': `Страница ${item}` })}
          />
        </Group>
      )}
    </Stack>
  );
}
