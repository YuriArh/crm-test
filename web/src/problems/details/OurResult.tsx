import { Stack, Text } from '@mantine/core';
import { formatInstant } from '@/requests/format';
import type { ExchangeProblem } from '@/shared/api';
import { useBusinessTimezone } from '@/shared/system';
import { Property, Section } from '@/shared/ui/parts';
import { compareResults, differences, fromCurrent, type ResultFields } from './comparison';

export const SNAPSHOT_TITLE = 'Наш итог при обнаружении';
export const NOW_TITLE = 'Наш итог сейчас';
export const SNAPSHOT_NOT_KEPT = 'Снимок на момент проблемы не сохранён';

/**
 * Our side of a problem: the snapshot kept when it was opened (`ourSide`), or — for a problem older than snapshots —
 * the request's result now, said to be so (docs/specs/conflict-history.md §2.4).
 */
export function ourSideOf(problem: ExchangeProblem): { title: string; result: ResultFields | null; note?: string } {
  return problem.ourSide
    ? { title: SNAPSHOT_TITLE, result: problem.ourSide.result }
    : { title: NOW_TITLE, result: fromCurrent(problem.currentResult), note: SNAPSHOT_NOT_KEPT };
}

/**
 * Whether «Итог сейчас» is shown apart from our side: after a decision, and whenever the final result now is not
 * the one kept in the snapshot. Without a snapshot our side is the result now already.
 */
export function showsResultNow(problem: ExchangeProblem): boolean {
  if (!problem.ourSide) return false;
  if (problem.status === 'RESOLVED') return true;
  const current = problem.currentResult;
  const finalNow = current && (current.businessStatus === 'SUCCEEDED' || current.businessStatus === 'REJECTED');
  return differences(problem.ourSide.result, finalNow ? fromCurrent(current) : null).length > 0;
}

/**
 * One result as a list of its non-empty fields: our side alone (a lost operation) or «Итог сейчас». `resultAt` is
 * when the request got it.
 */
export function OurResult({
  title,
  result,
  note,
  resultAt,
}: {
  title: string;
  result: ResultFields | null;
  note?: string;
  resultAt?: string | null;
}) {
  const timeZone = useBusinessTimezone();

  return (
    <Section title={title}>
      <Stack gap="xs">
        {note && (
          <Text size="xs" c="dimmed">
            {note}
          </Text>
        )}
        {result ? (
          <>
            {compareResults(result, null).map((row) => (
              <Property key={row.label} label={row.label}>
                <Text component="span" style={{ overflowWrap: 'anywhere' }}>
                  {row.left}
                </Text>
              </Property>
            ))}
            {resultAt && <Property label="Получен">{formatInstant(resultAt, timeZone)}</Property>}
          </>
        ) : (
          <Text c="dimmed">Итога нет</Text>
        )}
      </Stack>
    </Section>
  );
}

/** «Итог сейчас» — the request's result now, under the sides of a problem. */
export function ResultNow({ problem }: { problem: ExchangeProblem }) {
  return (
    <OurResult
      title="Итог сейчас"
      result={fromCurrent(problem.currentResult)}
      resultAt={problem.currentResult?.result?.resultAt}
    />
  );
}
