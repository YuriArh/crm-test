import { Stack } from '@mantine/core';
import { formatInstant } from '@/requests/format';
import type { ExchangeProblem } from '@/shared/api';
import { useBusinessTimezone } from '@/shared/system';
import { Property } from '@/shared/ui/parts';
import { fromMessage } from './comparison';
import { ourSideOf, ResultNow, showsResultNow } from './OurResult';
import { ResultComparison } from './ResultComparison';

/**
 * A conflict of two results: the carrier's version and our result as it was when the conflict was found, side by
 * side, with the differing fields marked. They stay so after a decision; the result the request has now is shown apart
 * («Итог сейчас»). The carrier's side is a feed record (`RESULT_CONFLICT`) or its answer to reconciliation
 * (`RECONCILIATION_CONFLICT`), which may have no result.
 */
export function ConflictView({ problem }: { problem: ExchangeProblem }) {
  const timeZone = useBusinessTimezone();
  const { message } = problem;
  const ours = ourSideOf(problem);
  const carrier = fromMessage(message);

  return (
    <Stack gap="md">
      <ResultComparison
        left={{
          title: 'Версия перевозчика',
          result: carrier,
          empty: 'У перевозчика итога ещё нет',
          extra: carrier && message?.occurredAt && (
            <Property label="Время у перевозчика">{formatInstant(message.occurredAt, timeZone)}</Property>
          ),
        }}
        right={{ title: ours.title, result: ours.result, empty: 'Итога нет', note: ours.note }}
        compare={problem.ourSide !== null}
      />
      {showsResultNow(problem) && <ResultNow problem={problem} />}
    </Stack>
  );
}
