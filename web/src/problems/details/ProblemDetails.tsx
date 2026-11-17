import { Anchor, Badge, Blockquote, Button, Group, Stack, Text } from '@mantine/core';
import { Link } from '@tanstack/react-router';
import type { ReactNode } from 'react';
import { formatInstant } from '@/requests/format';
import type { ExchangeProblem, ProblemDecision } from '@/shared/api';
import { useBusinessTimezone } from '@/shared/system';
import { CopyValue, Property } from '@/shared/ui/parts';
import { DECISION_ACTIONS, kindView, STATUS_VIEWS } from '../labels';
import { CarrierMessage } from './CarrierMessage';
import { ConflictView } from './ConflictView';
import { OurResult } from './OurResult';
import { ResolvedLine } from './ResolvedLine';

type Props = {
  problem: ExchangeProblem;
  /** The request's link; the card leaves it out (it is the request). */
  showRequest?: boolean;
  /** The kind's name; the panel leaves it out (its title says it). */
  showKind?: boolean;
  /** Called with a decision of `problem.actions`; without it no decision buttons are shown. */
  onDecide?: (decision: ProblemDecision) => void;
  /** Request actions shown next to the decisions (the card puts «Повторить отправку» here). */
  actions?: ReactNode;
};

const CONFLICTS = new Set<string>(['RESULT_CONFLICT', 'RECONCILIATION_CONFLICT']);
const RECORDS = new Set<string>(['INVALID_MESSAGE', 'MESSAGE_FAILED']);

/**
 * Everything about one problem, the same in the request card and in the problems panel (web-problems.md §7.1):
 * what happened and why, the carrier record or both sides of a conflict, how it was closed, and the decisions
 * dispatch allows now — the client never works out which decisions are possible.
 */
export function ProblemDetails({ problem, showRequest = true, showKind = true, onDecide, actions }: Props) {
  const timeZone = useBusinessTimezone();
  const kind = kindView(problem.kind);
  const { request } = problem;
  const status = STATUS_VIEWS[problem.status];
  const decisions = onDecide ? problem.actions.filter((decision) => DECISION_ACTIONS[decision] !== undefined) : [];

  return (
    <Stack gap="md">
      <Stack gap={4}>
        <Group gap="xs" wrap="wrap">
          {showKind && <Text fw={590}>{kind.label}</Text>}
          <Badge variant="dot" color={status.color} size="sm" radius="sm" tt="none" fw={510}>
            {status.label}
          </Badge>
        </Group>
        {kind.hint && (
          <Text size="sm" c="dimmed">
            {kind.hint}
          </Text>
        )}
      </Stack>

      <Group gap="xl" align="flex-start" wrap="wrap">
        <Property label="Причина">
          <Text component="span" style={{ overflowWrap: 'anywhere' }}>
            {problem.details}
          </Text>
        </Property>
        <Property label="Время открытия">{formatInstant(problem.openedAt, timeZone)}</Property>
        {showRequest && (
          <Property label="Заявка">
            {request ? (
              <Anchor renderRoot={(props) => <Link {...props} to="/requests/$id" params={{ id: request.id }} />}>
                {request.number}
              </Anchor>
            ) : (
              '—'
            )}
          </Property>
        )}
        {problem.operationId && (
          <Property label="Идентификатор операции">
            <CopyValue value={problem.operationId} label="идентификатор операции" mono />
          </Property>
        )}
      </Group>

      {CONFLICTS.has(problem.kind) && <ConflictView message={problem.message} current={problem.currentResult} />}
      {RECORDS.has(problem.kind) && problem.message && <CarrierMessage message={problem.message} />}
      {problem.kind === 'EXTERNAL_MISSING' && <OurResult current={problem.currentResult} />}

      {problem.status === 'RESOLVED' && (
        <Stack gap="xs">
          <ResolvedLine problem={problem} />
          {problem.comment && (
            <Blockquote p="sm" color="gray">
              {problem.comment}
            </Blockquote>
          )}
        </Stack>
      )}

      {(decisions.length > 0 || actions) && (
        <Group gap="sm" wrap="wrap">
          {decisions.map((decision) => (
            <Button
              key={decision}
              variant={decision === 'KEEP_OURS' || decision === 'ACKNOWLEDGED' ? 'default' : 'filled'}
              size="sm"
              onClick={() => onDecide?.(decision)}
            >
              {DECISION_ACTIONS[decision]}
            </Button>
          ))}
          {actions}
        </Group>
      )}
    </Stack>
  );
}
