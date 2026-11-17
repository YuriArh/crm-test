import { Text } from '@mantine/core';
import { formatInstant } from '@/requests/format';
import type { ExchangeProblem } from '@/shared/api';
import { useBusinessTimezone } from '@/shared/system';
import { DECISION_LABELS } from '../labels';

/** «Закрыта · 17.11, 14:05 · Оператор (Север) · Оставлен наш итог»; the system has no name. */
export function resolvedText(problem: ExchangeProblem, timeZone: string): string {
  const parts = ['Закрыта'];
  if (problem.resolvedAt) parts.push(formatInstant(problem.resolvedAt, timeZone));
  if (problem.resolvedBy) parts.push(problem.resolvedBy.displayName);
  if (problem.decision) parts.push(DECISION_LABELS[problem.decision] ?? problem.decision);
  return parts.join(' · ');
}

/** Who closed a problem, when and how. */
export function ResolvedLine({ problem }: { problem: ExchangeProblem }) {
  const timeZone = useBusinessTimezone();
  return (
    <Text size="sm" c="dimmed">
      {resolvedText(problem, timeZone)}
    </Text>
  );
}
