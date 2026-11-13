import {
  Anchor,
  Button,
  Card,
  Group,
  Loader,
  Pagination,
  Select,
  Skeleton,
  Stack,
  Table,
  Text,
  Title,
} from '@mantine/core';
import { Link, useNavigate, useSearchParams } from 'react-router';

import type { RequestListItem, Role } from '../api/types';
import { useCurrentUser } from '../auth/useAuth';
import { useBusinessTime } from '../system/useBusinessTime';
import { formatDate } from '../system/time';
import { EmptyState, ErrorAlert } from '../ui/states';
import { useRequestList } from './api';
import { hasFilters, parseListParams, sortOptions, toSearch, type ListParams } from './listParams';
import { businessStatuses, deliveryStatuses } from './statuses';
import { StatusBadges } from './StatusBadges';

const ANY = '';

export function RequestListPage() {
  const me = useCurrentUser();
  const [search, setSearch] = useSearchParams();
  const params = parseListParams(search);
  const list = useRequestList(params);
  const isInitiator = me.role === 'INITIATOR';

  const update = (change: Partial<ListParams>) => {
    // Any filter or sort change starts from the first page.
    const page = 'page' in change ? (change.page ?? 1) : 1;
    setSearch(toSearch({ ...params, ...change, page }));
  };

  const createButton = isInitiator && (
    <Button component={Link} to="/requests/new">
      Создать заявку
    </Button>
  );

  return (
    <Stack gap="md">
      <Group justify="space-between" align="center">
        <Group gap="xs">
          <Title order={1} size="h2">
            Заявки
          </Title>
          {list.isFetching && !list.isPending && <Loader size="xs" aria-label="Обновление" />}
        </Group>
        {createButton}
      </Group>

      <Group gap="sm" align="end" wrap="wrap">
        <Select
          label="Результат"
          data={[
            { value: ANY, label: 'Любой' },
            ...Object.entries(businessStatuses).map(([value, view]) => ({ value, label: view.label })),
          ]}
          value={params.businessStatus ?? ANY}
          onChange={(value) =>
            update({ businessStatus: (value || undefined) as ListParams['businessStatus'] })
          }
          allowDeselect={false}
          w={{ base: '100%', xs: 200 }}
        />
        {!isInitiator && (
          <Select
            label="Доставка"
            data={[
              { value: ANY, label: 'Любая' },
              ...Object.entries(deliveryStatuses).map(([value, view]) => ({ value, label: view.label })),
            ]}
            value={params.deliveryStatus ?? ANY}
            onChange={(value) =>
              update({ deliveryStatus: (value || undefined) as ListParams['deliveryStatus'] })
            }
            allowDeselect={false}
            w={{ base: '100%', xs: 220 }}
          />
        )}
        <Select
          label="Срок"
          data={[
            { value: ANY, label: 'Все' },
            { value: 'true', label: 'Просроченные' },
            { value: 'false', label: 'Не просроченные' },
          ]}
          value={params.overdue === undefined ? ANY : String(params.overdue)}
          onChange={(value) =>
            update({ overdue: value === 'true' ? true : value === 'false' ? false : undefined })
          }
          allowDeselect={false}
          w={{ base: '100%', xs: 180 }}
        />
        <Select
          label="Сортировка"
          data={sortOptions.map((option) => ({ ...option }))}
          value={params.sort}
          onChange={(value) => value && update({ sort: value })}
          allowDeselect={false}
          w={{ base: '100%', xs: 210 }}
        />
        {hasFilters(params) && (
          <Button variant="subtle" onClick={() => setSearch(new URLSearchParams())}>
            Сбросить фильтры
          </Button>
        )}
      </Group>

      {list.isPending ? (
        <Stack gap="xs" aria-label="Загрузка списка">
          {[0, 1, 2, 3].map((i) => (
            <Skeleton key={i} h={44} />
          ))}
        </Stack>
      ) : list.isError ? (
        <ErrorAlert error={list.error} onRetry={() => void list.refetch()} />
      ) : list.data.items.length === 0 ? (
        hasFilters(params) ? (
          <EmptyState
            title="Ничего не найдено"
            text="Под выбранные фильтры не подходит ни одна заявка."
            action={
              <Button variant="light" onClick={() => setSearch(new URLSearchParams())}>
                Сбросить фильтры
              </Button>
            }
          />
        ) : (
          <EmptyState
            title="Заявок пока нет"
            text={isInitiator ? 'Создайте первую заявку на доставку.' : 'В вашей службе ещё нет заявок.'}
            action={createButton}
          />
        )
      ) : (
        <>
          <RequestTable items={list.data.items} role={me.role} />
          <RequestCards items={list.data.items} role={me.role} />
          <Group justify="space-between" wrap="wrap">
            <Text size="sm" c="dimmed">
              Всего: {list.data.totalElements}
            </Text>
            {list.data.totalPages > 1 && (
              <Pagination
                total={list.data.totalPages}
                value={params.page}
                onChange={(page) => update({ page })}
                getControlProps={(control) => ({
                  'aria-label': control === 'next' ? 'Следующая страница' : 'Предыдущая страница',
                })}
              />
            )}
          </Group>
        </>
      )}
    </Stack>
  );
}

function Deadline({ item }: { item: RequestListItem }) {
  return (
    <Text size="sm" c={item.overdue ? 'red' : undefined} fw={item.overdue ? 600 : undefined}>
      {formatDate(item.deadline)}
    </Text>
  );
}

function RequestTable({ items, role }: { items: RequestListItem[]; role: Role }) {
  const navigate = useNavigate();
  const { formatInstant } = useBusinessTime();
  return (
    <Table.ScrollContainer minWidth={760} visibleFrom="sm">
      <Table highlightOnHover verticalSpacing="sm">
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Номер</Table.Th>
            <Table.Th>Груз</Table.Th>
            <Table.Th>Срок</Table.Th>
            <Table.Th>Статус</Table.Th>
            {role === 'OPERATOR' && <Table.Th>Инициатор</Table.Th>}
            <Table.Th>Исполнитель</Table.Th>
            <Table.Th>Создана</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {items.map((item) => (
            <Table.Tr
              key={item.id}
              style={{ cursor: 'pointer' }}
              onClick={(event) => {
                if (!(event.target as HTMLElement).closest('a, button')) {
                  void navigate(`/requests/${item.id}`);
                }
              }}
            >
              <Table.Td>
                <Anchor component={Link} to={`/requests/${item.id}`} fw={600}>
                  {item.number}
                </Anchor>
              </Table.Td>
              <Table.Td maw={260}>
                <Text size="sm" truncate>
                  {item.cargoDescription}
                </Text>
              </Table.Td>
              <Table.Td>
                <Deadline item={item} />
              </Table.Td>
              <Table.Td>
                <StatusBadges
                  business={item.businessStatus}
                  delivery={item.deliveryStatus}
                  overdue={item.overdue}
                  role={role}
                />
              </Table.Td>
              {role === 'OPERATOR' && <Table.Td>{item.owner.displayName}</Table.Td>}
              <Table.Td>
                {item.assignee?.displayName ?? (
                  <Text size="sm" c="dimmed">
                    не назначен
                  </Text>
                )}
              </Table.Td>
              <Table.Td>
                <Text size="sm">{formatInstant(item.createdAt)}</Text>
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </Table.ScrollContainer>
  );
}

/** Narrow screens (390 px and up): one card per request instead of a wide table. */
function RequestCards({ items, role }: { items: RequestListItem[]; role: Role }) {
  return (
    <Stack gap="sm" hiddenFrom="sm">
      {items.map((item) => (
        <Card key={item.id} withBorder padding="sm" component={Link} to={`/requests/${item.id}`}>
          <Stack gap={6}>
            <Group justify="space-between" wrap="nowrap">
              <Text fw={600}>{item.number}</Text>
              <Group gap={4} wrap="nowrap">
                <Text size="sm" c="dimmed">
                  срок
                </Text>
                <Deadline item={item} />
              </Group>
            </Group>
            <Text size="sm" lineClamp={2}>
              {item.cargoDescription}
            </Text>
            <StatusBadges
              business={item.businessStatus}
              delivery={item.deliveryStatus}
              overdue={item.overdue}
              role={role}
            />
          </Stack>
        </Card>
      ))}
    </Stack>
  );
}
