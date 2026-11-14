import { ActionIcon, Alert, Box, Button, Group, Pagination, Progress, Stack, Text, Title } from '@mantine/core';
import { IconChevronLeft, IconChevronRight, IconPlus } from '@tabler/icons-react';
import { useQuery } from '@tanstack/react-query';
import { getRouteApi, Link } from '@tanstack/react-router';
import { useEffect } from 'react';
import { useCurrentUser } from '@/auth';
import { isApiError, NetworkError, type Role } from '@/shared/api';
import { useBusinessTimezone } from '@/shared/system';
import { requestListQueryOptions } from './api';
import classes from './RequestList.module.css';
import { RequestCards, RequestCardsSkeleton } from './RequestCards';
import { RequestFilterBar, RequestFilterToolbar } from './RequestFilters';
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

/** Creating requests is the initiator's job (the server allows it to initiators only). */
function NewRequestButton({ variant = 'filled' }: { variant?: 'filled' | 'default' }) {
  return (
    <Button component={Link} to="/requests/new" variant={variant} leftSection={<IconPlus size={16} stroke={1.8} />}>
      Новая заявка
    </Button>
  );
}

function EmptyMessage({ role }: { role: Role }) {
  return (
    <Stack align="center" gap="sm" py="xl">
      <Text fw={510}>{role === 'OPERATOR' ? 'В службе пока нет заявок' : 'Заявок пока нет'}</Text>
      {role === 'INITIATOR' && <NewRequestButton variant="default" />}
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
  const filterProps = {
    search,
    role: me.role,
    activeCount,
    onFilters: changeFilters,
    onReset: resetFilters,
    onSort: changeSort,
  };

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
  const currentPage = Math.min(page, Math.max(1, data?.totalPages ?? 1));
  const goToPage = (next: number) => void navigate({ search: (prev) => withPage(clean(prev), next) });

  return (
    <Stack gap="md">
      <Group justify="space-between" wrap="nowrap">
        <Group gap="sm" align="baseline">
          <Title order={2}>Заявки</Title>
          {data && (
            <Text c="dimmed" size="lg" data-testid="request-total">
              {data.totalElements}
            </Text>
          )}
        </Group>
        {me.role === 'INITIATOR' && <NewRequestButton />}
      </Group>

      {/* Desktop and phone layouts are switched by CSS, not by a JS media query: no jump on load. */}
      <Box visibleFrom="md" data-testid="filter-bar">
        <RequestFilterBar {...filterProps} />
      </Box>
      <Box hiddenFrom="md" data-testid="filter-toolbar">
        <RequestFilterToolbar {...filterProps} />
      </Box>

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

        {!data && !error && (
          <>
            <Box visibleFrom="md">
              <RequestTableSkeleton role={me.role} />
            </Box>
            <Box hiddenFrom="md">
              <RequestCardsSkeleton />
            </Box>
          </>
        )}

        {data && data.totalElements === 0 && !filtered && <EmptyMessage role={me.role} />}
        {data && data.totalElements === 0 && filtered && (
          <Stack align="center" gap="sm" py="xl">
            <Text fw={510}>Ничего не найдено</Text>
            <Button variant="default" onClick={resetFilters}>
              Сбросить фильтры
            </Button>
          </Stack>
        )}
        {data && data.items.length > 0 && (
          <>
            <Box visibleFrom="md">
              <RequestTable items={data.items} role={me.role} timeZone={timeZone} />
            </Box>
            <Box hiddenFrom="md">
              <RequestCards items={data.items} role={me.role} />
            </Box>
          </>
        )}
      </Box>

      {data && data.totalElements > 0 && (
        <>
          <Group justify="space-between" gap="sm" visibleFrom="md">
            <Text size="md" c="dimmed">
              {formatPageRange(data.page, data.size, data.totalElements)}
            </Text>
            <Pagination
              total={data.totalPages}
              value={currentPage}
              onChange={goToPage}
              size="sm"
              hideWithOnePage
              getControlProps={(control) => ({
                'aria-label': control === 'next' ? 'Следующая страница' : 'Предыдущая страница',
              })}
              getItemProps={(item) => ({ 'aria-label': `Страница ${item}` })}
            />
          </Group>
          {data.totalPages > 1 && (
            <Group justify="center" gap="md" hiddenFrom="md">
              <ActionIcon
                variant="default"
                size="xl"
                aria-label="Предыдущая страница"
                disabled={currentPage <= 1}
                onClick={() => goToPage(currentPage - 1)}
              >
                <IconChevronLeft size="1.2em" />
              </ActionIcon>
              <Text size="lg" className={classes.pageCounter}>
                {currentPage} / {data.totalPages}
              </Text>
              <ActionIcon
                variant="default"
                size="xl"
                aria-label="Следующая страница"
                disabled={currentPage >= data.totalPages}
                onClick={() => goToPage(currentPage + 1)}
              >
                <IconChevronRight size="1.2em" />
              </ActionIcon>
            </Group>
          )}
        </>
      )}
    </Stack>
  );
}
