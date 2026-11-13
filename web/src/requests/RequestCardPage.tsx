import { Anchor, Grid, Group, Loader, Paper, Skeleton, Stack, Text, Title } from '@mantine/core';
import { Link, useParams } from 'react-router';

import { isApiError } from '../api/errors';
import type { RequestCard, Role } from '../api/types';
import { useCurrentUser } from '../auth/useAuth';
import { useBusinessTime } from '../system/useBusinessTime';
import { formatDate, formatPrice, formatWeight } from '../system/time';
import { EmptyState, ErrorAlert } from '../ui/states';
import { useRequestCard } from './api';
import { RequestEditPanel } from './RequestEditPanel';
import { acknowledgedVia, businessStatuses, deliveryView, shouldPoll } from './statuses';
import { StatusBadges } from './StatusBadges';

export function RequestCardPage() {
  const { id = '' } = useParams();
  const me = useCurrentUser();
  const card = useRequestCard(id);

  const back = (
    <Anchor component={Link} to="/requests" size="sm">
      ← Все заявки
    </Anchor>
  );

  if (card.isPending) {
    return (
      <Stack gap="md" aria-label="Загрузка заявки">
        {back}
        <Skeleton h={36} w={240} />
        <Skeleton h={220} />
      </Stack>
    );
  }
  if (card.isError) {
    // A request outside the user's scope answers 404 too, so it is indistinguishable from a missing one.
    return isApiError(card.error, 'NOT_FOUND') ? (
      <EmptyState title="Заявка не найдена" text="Её нет или она вам недоступна." action={back} />
    ) : (
      <Stack>
        {back}
        <ErrorAlert error={card.error} onRetry={() => void card.refetch()} />
      </Stack>
    );
  }

  const data = card.data;
  // An operator sees only requests of their own service, so they may assign any request they can open.
  const canAssign = me.role === 'OPERATOR';

  return (
    <Stack gap="md">
      {back}
      <Group justify="space-between" align="center" wrap="wrap">
        <Group gap="sm" align="center">
          <Title order={1} size="h2">
            Заявка {data.number}
          </Title>
          <StatusBadges
            business={data.businessStatus}
            delivery={data.deliveryStatus}
            overdue={data.overdue}
            role={me.role}
          />
        </Group>
        {shouldPoll(data) && (
          <Group gap={6} c="dimmed">
            <Loader size={12} />
            <Text size="xs">Статус обновляется автоматически</Text>
          </Group>
        )}
      </Group>

      <Grid gutter="md">
        <Grid.Col span={{ base: 12, md: 7 }}>
          <Stack gap="md">
            <ContentSection card={data} />
            <ResultSection card={data} role={me.role} />
            {data.exchange && <ExchangeSection card={data} />}
          </Stack>
        </Grid.Col>
        <Grid.Col span={{ base: 12, md: 5 }}>
          <Section title="Работа с заявкой">
            <Stack gap="sm">
              <Detail label="Инициатор" value={data.owner.displayName} />
              <RequestEditPanel card={data} canAssign={canAssign} />
            </Stack>
          </Section>
        </Grid.Col>
      </Grid>
    </Stack>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <Paper withBorder p={{ base: 'sm', sm: 'md' }} radius="md" component="section" aria-label={title}>
      <Title order={2} size="h4" mb="sm">
        {title}
      </Title>
      {children}
    </Paper>
  );
}

function Detail({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div>
      <Text size="xs" c="dimmed">
        {label}
      </Text>
      <Text size="sm" component="div" style={{ overflowWrap: 'anywhere' }}>
        {value}
      </Text>
    </div>
  );
}

function ContentSection({ card }: { card: RequestCard }) {
  const { formatInstant } = useBusinessTime();
  return (
    <Section title="Груз и доставка">
      <Stack gap="sm">
        <Detail label="Груз" value={card.cargoDescription} />
        <Group gap="xl" wrap="wrap">
          <Detail label="Вес" value={formatWeight(card.weightKg)} />
          <Detail
            label="Срок"
            value={
              <Text span size="sm" c={card.overdue ? 'red' : undefined} fw={card.overdue ? 600 : undefined}>
                {formatDate(card.deadline)}
                {card.overdue && ' — просрочена'}
              </Text>
            }
          />
        </Group>
        <Detail label="Адрес доставки" value={card.deliveryAddress} />
        <Group gap="xl" wrap="wrap">
          <Detail label="Создана" value={formatInstant(card.createdAt)} />
          <Detail label="Изменена" value={formatInstant(card.updatedAt)} />
        </Group>
      </Stack>
    </Section>
  );
}

function ResultSection({ card, role }: { card: RequestCard; role: Role }) {
  const { formatInstant } = useBusinessTime();
  const result = card.result;
  return (
    <Section title="Результат">
      {result === null ? (
        <Stack gap={4}>
          <Text size="sm">Результата пока нет.</Text>
          <Text size="sm" c="dimmed">
            Доставка: {deliveryView(card.deliveryStatus, role).hint} Результат:{' '}
            {businessStatuses[card.businessStatus].hint}
          </Text>
        </Stack>
      ) : card.businessStatus === 'REJECTED' ? (
        <Stack gap="sm">
          <Detail label="Причина отказа" value={result.rejectionReason ?? '—'} />
          {result.resultAt && <Detail label="Получен" value={formatInstant(result.resultAt)} />}
        </Stack>
      ) : (
        <Group gap="xl" wrap="wrap">
          <Detail label="Номер отправления" value={result.trackingNumber ?? '—'} />
          <Detail label="Стоимость" value={result.price === null ? '—' : formatPrice(result.price)} />
          {result.resultAt && <Detail label="Получен" value={formatInstant(result.resultAt)} />}
        </Group>
      )}
    </Section>
  );
}

function ExchangeSection({ card }: { card: RequestCard }) {
  const { formatInstant } = useBusinessTime();
  const exchange = card.exchange!;
  return (
    <Section title="Обмен с перевозчиком">
      <Stack gap="sm">
        <Detail label="Идентификатор операции" value={<code>{card.operationId}</code>} />
        <Group gap="xl" wrap="wrap">
          <Detail label="Попыток отправки" value={exchange.attempts} />
          {exchange.nextAttemptAt && (
            <Detail label="Следующая попытка" value={formatInstant(exchange.nextAttemptAt)} />
          )}
          {exchange.acknowledgedAt && (
            <Detail
              label="Получение подтверждено"
              value={`${formatInstant(exchange.acknowledgedAt)}${
                exchange.acknowledgedVia
                  ? ` (${acknowledgedVia[exchange.acknowledgedVia] ?? exchange.acknowledgedVia})`
                  : ''
              }`}
            />
          )}
        </Group>
        {exchange.lastError && (
          <Detail
            label="Последняя ошибка"
            value={
              <Text size="sm" c="red">
                {exchange.lastError}
              </Text>
            }
          />
        )}
      </Stack>
    </Section>
  );
}
