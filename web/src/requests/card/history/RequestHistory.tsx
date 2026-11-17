import { Button, Group, SegmentedControl, Stack, Text, Timeline, Title } from '@mantine/core';
import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useId, useRef, useState } from 'react';
import { useCurrentUser } from '@/auth';
import type { HistoryEntry, RequestCard } from '@/shared/api';
import { useBusinessTimezone } from '@/shared/system';
import { Section } from '@/shared/ui/parts';
import { formatInstant } from '../../format';
import { requestKeys } from '../../keys';
import { historyQueryOptions, historySignature } from './api';
import { changeView } from './changes';
import { changesOf, entryView, isExchange, isPeople } from './labels';

type Filter = 'all' | 'people' | 'exchange';

const FILTERS: { label: string; value: Filter }[] = [
  { label: 'Все', value: 'all' },
  { label: 'Действия людей', value: 'people' },
  { label: 'Обмен', value: 'exchange' },
];

const MATCHES: Record<Filter, (entry: HistoryEntry) => boolean> = {
  all: () => true,
  people: isPeople,
  exchange: isExchange,
};

/**
 * «История» — what happened to the request and its exchange with the carrier, newest first
 * (docs/specs/request-history.md §2.6). The server sends what the viewer may see; an operator can narrow it to the
 * people's actions or to the exchange. It is asked again when the card shows a change.
 */
export function RequestHistory({ card }: { card: RequestCard }) {
  const me = useCurrentUser();
  const timeZone = useBusinessTimezone();
  const queryClient = useQueryClient();
  const [filter, setFilter] = useState<Filter>('all');
  const history = useInfiniteQuery(historyQueryOptions(card.id));

  const signature = historySignature(card);
  const shown = useRef(signature);
  useEffect(() => {
    if (shown.current === signature) return;
    shown.current = signature;
    void queryClient.invalidateQueries({ queryKey: requestKeys.history(card.id) });
  }, [signature, card.id, queryClient]);

  const operator = me.role === 'OPERATOR';
  const entries = (history.data?.pages ?? []).flatMap((page) => page.items).filter(MATCHES[filter]);

  return (
    <Section title="История">
      <Stack gap="md">
        {operator && (
          <SegmentedControl
            data={FILTERS}
            value={filter}
            onChange={(value) => setFilter(value as Filter)}
            size="xs"
            fullWidth
          />
        )}
        {history.isPending ? (
          <Text size="sm" c="dimmed">
            Загрузка истории…
          </Text>
        ) : history.isError ? (
          <Group gap="sm">
            <Text size="sm" c="red">
              Не удалось загрузить историю
            </Text>
            <Button variant="default" size="xs" onClick={() => void history.refetch()}>
              Повторить
            </Button>
          </Group>
        ) : entries.length === 0 ? (
          <Text size="sm" c="dimmed">
            Таких записей нет
          </Text>
        ) : (
          <Timeline role="list">
            {entries.map((entry) => (
              <Entry key={`${entry.kind}:${entry.id}`} entry={entry} timeZone={timeZone} />
            ))}
          </Timeline>
        )}
        {history.hasNextPage && (
          <Button
            variant="default"
            size="xs"
            onClick={() => void history.fetchNextPage()}
            loading={history.isFetchingNextPage}
            style={{ alignSelf: 'flex-start' }}
          >
            Показать ещё
          </Button>
        )}
      </Stack>
    </Section>
  );
}

function Entry({ entry, timeZone }: { entry: HistoryEntry; timeZone: string }) {
  const view = entryView(entry, timeZone);
  const [open, setOpen] = useState(false);
  const detailsId = useId();

  return (
    <Timeline.Item
      role="listitem"
      color={view.color}
      title={
        <Title order={4} size="sm" fw={590} style={{ overflowWrap: 'anywhere' }}>
          {view.title}
        </Title>
      }
    >
      <Stack gap={2}>
        <Text size="xs" c="dimmed">
          {formatInstant(entry.at, timeZone)} · {entry.actor?.displayName ?? 'Система'}
        </Text>
        {changesOf(entry).map((change) => {
          const field = changeView(change);
          return (
            <Text key={change.field} size="sm" lineClamp={field.long ? 3 : undefined} style={{ overflowWrap: 'anywhere' }}>
              <Text component="span" size="sm" c="dimmed">
                {field.label}:
              </Text>{' '}
              {field.from} → {field.to}
            </Text>
          );
        })}
        {view.lines.map((line) => (
          <Text key={line} size="sm" c="dimmed" style={{ overflowWrap: 'anywhere' }}>
            {line}
          </Text>
        ))}
        {view.details.length > 0 && (
          <>
            <Button
              variant="subtle"
              size="compact-xs"
              aria-expanded={open}
              aria-controls={detailsId}
              onClick={() => setOpen((value) => !value)}
              style={{ alignSelf: 'flex-start' }}
            >
              Подробнее
            </Button>
            {open && (
              <Stack gap={2} id={detailsId}>
                {view.details.map((line) => (
                  <Text key={line} size="xs" ff="monospace" style={{ overflowWrap: 'anywhere', whiteSpace: 'pre-wrap' }}>
                    {line}
                  </Text>
                ))}
              </Stack>
            )}
          </>
        )}
      </Stack>
    </Timeline.Item>
  );
}
