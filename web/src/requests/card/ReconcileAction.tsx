import { Alert, Badge, Box, Button, Group, Loader, Stack, Text } from '@mantine/core';
import { useQueryClient } from '@tanstack/react-query';
import { useRef, useState, type MouseEvent } from 'react';
import { describeActionFailure, type ActionFailure } from '@/shared/actions/errors';
import { useActionKeys } from '@/shared/actions/idempotency';
import type { Reconciliation, RequestCard } from '@/shared/api';
import { useBusinessTimezone } from '@/shared/system';
import { formatInstant } from '../format';
import { requestKeys } from '../keys';
import { useReconcile } from './api';
import { OUTCOME_VIEWS } from './reconciliation';

// A check never meets an invalid state; `stale` is listed for the shared table only.
const TEXTS = { stale: 'Состояние заявки изменилось', gone: 'Заявка не найдена' };

/**
 * «Сверить сейчас» (web-problems.md §8.3): shown when dispatch put `RECONCILE` into the card's actions; no dialog —
 * it changes nothing at the carrier. One press is one intent: a new key per press (an old key would replay the
 * recorded check instead of asking the carrier again), the same key when the answer of that press was lost.
 *
 * The outcome stays under the button while the card is open. The button keeps the focus while the carrier is
 * asked (it looks disabled but is not, so the focus is not lost), and the outcome is announced (`role="status"`).
 */
export function ReconcileAction({ card }: { card: RequestCard }) {
  const queryClient = useQueryClient();
  const timeZone = useBusinessTimezone();
  const reconcile = useReconcile(card.id);
  const keys = useActionKeys((press: number) => String(press));
  const press = useRef(0);
  const unknown = useRef(false);
  const [check, setCheck] = useState<Reconciliation | null>(null);
  const [failure, setFailure] = useState<ActionFailure | null>(null);

  if (!card.actions.includes('RECONCILE')) return null;

  const pending = reconcile.isPending;
  const run = (event: MouseEvent) => {
    if (pending) {
      event.preventDefault();
      return;
    }
    // A press whose outcome is unknown is repeated as itself; any other press is a new question to the carrier.
    if (!unknown.current) press.current += 1;
    const current = press.current;
    reconcile.mutate(keys.keyFor(current), {
      onSuccess: (answer) => {
        keys.forget(current);
        unknown.current = false;
        setFailure(null);
        setCheck(answer);
      },
      onError: (error) => {
        const described = describeActionFailure(error, TEXTS);
        unknown.current = described.kind === 'transient';
        if (!unknown.current) keys.forget(current);
        if (described.kind === 'gone') {
          void queryClient.invalidateQueries({ queryKey: requestKeys.card(card.id) });
          return;
        }
        if (described.kind === 'session') return;
        setFailure(described);
      },
    });
  };

  const outcome = check ? OUTCOME_VIEWS[check.outcome] : null;

  return (
    <Stack gap="xs">
      <Group>
        <Button
          variant="default"
          size="sm"
          onClick={run}
          aria-disabled={pending}
          data-disabled={pending || undefined}
          leftSection={pending ? <Loader size="xs" /> : undefined}
        >
          Сверить сейчас
        </Button>
      </Group>
      <Box role="status" aria-live="polite">
        {failure && 'message' in failure ? (
          <Alert color="red" variant="light" p="xs">
            {failure.message}
          </Alert>
        ) : (
          check &&
          outcome && (
            <Stack gap={4}>
              <Group gap="xs" wrap="wrap">
                <Badge color={outcome.color} variant="light" size="sm" radius="sm" tt="none" fw={510}>
                  {outcome.label}
                </Badge>
                {check.acknowledged && (
                  <Text component="span" size="sm" c="dimmed">
                    · получение подтверждено
                  </Text>
                )}
              </Group>
              <Text size="sm" style={{ overflowWrap: 'anywhere' }}>
                {check.details}
              </Text>
              <Text size="xs" c="dimmed">
                {formatInstant(check.checkedAt, timeZone)}
              </Text>
            </Stack>
          )
        )}
      </Box>
    </Stack>
  );
}
