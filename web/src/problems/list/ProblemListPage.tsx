import { Alert, Box, Button, Group, Pagination, Skeleton, Stack, Text, Title } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { getRouteApi, Outlet } from '@tanstack/react-router';
import { useEffect } from 'react';
import { isApiError } from '@/shared/api';
import { useBusinessTimezone } from '@/shared/system';
import { problemsQueryOptions } from '../api';
import { ProblemFilterBar, ProblemFilterSheet } from './ProblemFilters';
import { ProblemCards, ProblemTable } from './ProblemRows';
import {
  activeFilterCount,
  parseProblemSearch,
  toProblemApiParams,
  withKinds,
  withoutFilters,
  withPage,
  withStatus,
} from './search';

const route = getRouteApi('/_authenticated/problems');

const LOAD_ERROR = 'Не удалось загрузить проблемы. Проверьте связь';

/**
 * The problems of the operator's service and of records that name no request (web-problems.md §5). Filters and the
 * page live in the address; a row opens the problem's panel (the child route) over the list.
 */
export function ProblemListPage() {
  const timeZone = useBusinessTimezone();
  const rawSearch = route.useSearch();
  const search = parseProblemSearch(rawSearch);
  const navigate = route.useNavigate();
  const query = useQuery(problemsQueryOptions(toProblemApiParams(search)));
  const { data, error } = query;
  const filtered = activeFilterCount(search) > 0;

  const go = (next: (prev: Record<string, unknown>) => Record<string, unknown>) =>
    void navigate({ to: '/problems', search: (prev) => next(parseProblemSearch(prev)) });
  const filterProps = {
    search,
    onStatus: (status: Parameters<typeof withStatus>[1]) => go((prev) => withStatus(prev, status)),
    onKinds: (kinds: Parameters<typeof withKinds>[1]) => go((prev) => withKinds(prev, kinds)),
    onReset: () => go(() => withoutFilters()),
  };

  // A broken address that still reached the API (400): back to the defaults.
  const badRequest = isApiError(error) && error.status === 400;
  useEffect(() => {
    if (badRequest && filtered) void navigate({ to: '/problems', search: {}, replace: true });
  }, [badRequest, filtered, navigate]);

  // Fewer problems than the address expects (some were closed): go to the last page that exists.
  const page = search.page ?? 1;
  const lastPage = data && !query.isPlaceholderData ? Math.max(1, data.totalPages) : undefined;
  useEffect(() => {
    if (lastPage !== undefined && page > lastPage) {
      void navigate({ to: '/problems', search: (prev) => withPage(parseProblemSearch(prev), lastPage), replace: true });
    }
  }, [lastPage, page, navigate]);

  return (
    <Stack gap="md">
      <Group gap="sm" align="baseline">
        <Title order={2}>Проблемы</Title>
        {data && (
          <Text c="dimmed" size="lg" data-testid="problem-total">
            {data.totalElements}
          </Text>
        )}
      </Group>

      <Box visibleFrom="md">
        <ProblemFilterBar {...filterProps} />
      </Box>
      <Box hiddenFrom="md">
        <ProblemFilterSheet {...filterProps} />
      </Box>

      {error && data && (
        <Alert color="yellow" title="Список мог устареть">
          <Group justify="space-between" gap="sm">
            <Text size="md">{LOAD_ERROR}</Text>
            <Button size="xs" variant="default" onClick={() => void query.refetch()}>
              Повторить
            </Button>
          </Group>
        </Alert>
      )}
      {!data && error && !badRequest && (
        <Stack align="center" gap="sm" py="xl" role="alert">
          <Text>{LOAD_ERROR}</Text>
          <Button variant="default" onClick={() => void query.refetch()}>
            Повторить
          </Button>
        </Stack>
      )}
      {!data && !error && (
        <Stack gap="sm" data-testid="problems-skeleton">
          {Array.from({ length: 5 }, (_, index) => (
            <Skeleton key={index} h="2.5rem" radius="sm" />
          ))}
        </Stack>
      )}

      {data && data.totalElements === 0 && !filtered && (
        <Text ta="center" py="xl" fw={510}>
          Открытых проблем нет. Обмен идёт без вмешательства
        </Text>
      )}
      {data && data.totalElements === 0 && filtered && (
        <Stack align="center" gap="sm" py="xl">
          <Text fw={510}>Ничего не найдено</Text>
          <Button variant="default" onClick={filterProps.onReset}>
            Сбросить фильтры
          </Button>
        </Stack>
      )}
      {data && data.items.length > 0 && (
        <>
          <Box visibleFrom="md">
            <ProblemTable items={data.items} timeZone={timeZone} />
          </Box>
          <Box hiddenFrom="md">
            <ProblemCards items={data.items} timeZone={timeZone} />
          </Box>
        </>
      )}

      {data && data.totalPages > 1 && (
        <Group justify="center">
          <Pagination
            total={data.totalPages}
            value={Math.min(page, data.totalPages)}
            onChange={(next) => go((prev) => withPage(prev, next))}
            size="sm"
            getControlProps={(control) => ({
              'aria-label': control === 'next' ? 'Следующая страница' : 'Предыдущая страница',
            })}
            getItemProps={(item) => ({ 'aria-label': `Страница ${item}` })}
          />
        </Group>
      )}

      <Outlet />
    </Stack>
  );
}
