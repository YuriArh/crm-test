import { Anchor, Badge, Group, Stack, Table, Text } from '@mantine/core';
import { Link } from '@tanstack/react-router';
import { formatInstant } from '@/requests/format';
import type { ExchangeProblem } from '@/shared/api';
import { DECISION_LABELS, kindView, STATUS_VIEWS } from '../labels';
import classes from './ProblemList.module.css';

function decisionText(problem: ExchangeProblem): string {
  if (!problem.decision) return '';
  const decision = DECISION_LABELS[problem.decision] ?? problem.decision;
  return problem.resolvedBy ? `${decision} · ${problem.resolvedBy.displayName}` : decision;
}

function StatusDot({ problem }: { problem: ExchangeProblem }) {
  const status = STATUS_VIEWS[problem.status];
  return (
    <Badge variant="dot" color={status.color} size="sm" radius="sm" tt="none" fw={510}>
      {status.label}
    </Badge>
  );
}

function RequestLink({ problem }: { problem: ExchangeProblem }) {
  const { request } = problem;
  if (!request) return <Text c="dimmed">—</Text>;
  return (
    <Anchor
      className={classes.above}
      ff="monospace"
      size="sm"
      renderRoot={(props) => <Link {...props} to="/requests/$id" params={{ id: request.id }} />}
    >
      {request.number}
    </Anchor>
  );
}

type Props = { items: ExchangeProblem[]; timeZone: string };

/** Problems on a desktop: each row opens the problem's panel; the request's number opens its card. */
export function ProblemTable({ items, timeZone }: Props) {
  return (
    <Table className={classes.table} highlightOnHover={false} verticalSpacing="sm">
      <Table.Thead>
        <Table.Tr>
          <Table.Th>Вид</Table.Th>
          <Table.Th>Заявка</Table.Th>
          <Table.Th>Причина</Table.Th>
          <Table.Th>Открыта</Table.Th>
          <Table.Th>Решение</Table.Th>
        </Table.Tr>
      </Table.Thead>
      <Table.Tbody>
        {items.map((problem) => (
          <Table.Tr key={problem.id} className={classes.row}>
            <Table.Td>
              <Stack gap={4}>
                <Link to="/problems/$id" params={{ id: problem.id }} search={true} className={classes.rowLink}>
                  {kindView(problem.kind).label}
                </Link>
                <div>
                  <StatusDot problem={problem} />
                </div>
              </Stack>
            </Table.Td>
            <Table.Td>
              <RequestLink problem={problem} />
            </Table.Td>
            <Table.Td className={classes.details} title={problem.details}>
              {problem.details}
            </Table.Td>
            <Table.Td style={{ whiteSpace: 'nowrap' }}>{formatInstant(problem.openedAt, timeZone)}</Table.Td>
            <Table.Td>
              <Text size="sm" c="dimmed">
                {decisionText(problem)}
              </Text>
            </Table.Td>
          </Table.Tr>
        ))}
      </Table.Tbody>
    </Table>
  );
}

/** Problems on a phone: one card per problem, each a link to its panel. */
export function ProblemCards({ items, timeZone }: Props) {
  return (
    <Stack gap="md" data-testid="problem-cards">
      {items.map((problem) => (
        <Link key={problem.id} to="/problems/$id" params={{ id: problem.id }} search={true} className={classes.card}>
          <Stack gap="xs">
            <Group justify="space-between" gap="sm" wrap="nowrap">
              <Text fw={510}>{kindView(problem.kind).label}</Text>
              <StatusDot problem={problem} />
            </Group>
            <Text size="sm" lineClamp={2} style={{ overflowWrap: 'anywhere' }}>
              {problem.details}
            </Text>
            <Group gap="sm" wrap="wrap">
              <Text size="sm" ff="monospace">
                {problem.request?.number ?? 'Без заявки'}
              </Text>
              <Text size="sm" c="dimmed">
                {formatInstant(problem.openedAt, timeZone)}
              </Text>
            </Group>
            {problem.decision && (
              <Text size="sm" c="dimmed">
                {decisionText(problem)}
              </Text>
            )}
          </Stack>
        </Link>
      ))}
    </Stack>
  );
}
