import { Accordion, Badge, Code, Group, Stack, Text } from '@mantine/core';
import { differences, ResultComparison } from '@/problems';
import type { Reconciliation } from '@/shared/api';
import { useBusinessTimezone } from '@/shared/system';
import { Property, Section } from '@/shared/ui/parts';
import { formatInstant } from '../format';
import { carrierStateLabel, OUTCOME_VIEWS, triggerLabel } from './reconciliation';

/**
 * «Сверки» — the latest reconciliation checks of the request, newest first, as dispatch sends them (web-problems.md
 * §8.3). The row shows the details already: a red `CONFLICT` with no problem opened («decided earlier») explains
 * itself without opening, and says what differed. An opened check shows both results of that moment
 * (conflict-history.md §2.4), so a conflict stays readable after it was decided. Rows open from the keyboard (they
 * are the accordion's buttons).
 */
export function ReconciliationHistory({ checks }: { checks: Reconciliation[] }) {
  const timeZone = useBusinessTimezone();

  return (
    <Section title="Сверки">
      {checks.length === 0 ? (
        <Text size="sm" c="dimmed">
          Сверок ещё не было
        </Text>
      ) : (
        <Accordion variant="separated" chevronPosition="left" multiple>
          {checks.map((check) => {
            const outcome = OUTCOME_VIEWS[check.outcome];
            const summary = conflictSummary(check);
            return (
              <Accordion.Item key={check.id} value={check.id}>
                <Accordion.Control>
                  <Stack gap={2} miw={0}>
                    <Group gap="xs" wrap="wrap">
                      <Text component="span" size="sm">
                        {formatInstant(check.checkedAt, timeZone)}
                      </Text>
                      <Badge color={outcome.color} variant="light" size="sm" radius="sm" tt="none" fw={510}>
                        {outcome.label}
                      </Badge>
                    </Group>
                    <Text component="span" size="xs" c="dimmed">
                      {triggerLabel(check.trigger, check.performedBy)}
                    </Text>
                    {summary && (
                      <Text component="span" size="xs" c="red" style={{ overflowWrap: 'anywhere' }}>
                        {summary}
                      </Text>
                    )}
                    <Text component="span" size="xs" c="dimmed" lineClamp={1} style={{ overflowWrap: 'anywhere' }}>
                      {check.details}
                    </Text>
                  </Stack>
                </Accordion.Control>
                <Accordion.Panel>
                  <CheckDetails check={check} timeZone={timeZone} />
                </Accordion.Panel>
              </Accordion.Item>
            );
          })}
        </Accordion>
      )}
    </Section>
  );
}

/** What differed in a conflict, said in its row so it reads without opening; nothing for other outcomes. */
function conflictSummary(check: Reconciliation): string | null {
  if (check.outcome !== 'CONFLICT') return null;
  if (check.carrierResult === null) return 'Итог есть только у нас';
  const fields = differences(check.carrierResult, check.localResult);
  return fields.length === 0 ? null : `Отличается: ${fields.join(', ')}`;
}

/** The whole check: its details, the carrier's answer, and both results of that moment side by side. */
function CheckDetails({ check, timeZone }: { check: Reconciliation; timeZone: string }) {
  const result = check.carrierResult;
  return (
    <Stack gap="sm">
      <Property label="Подробности">
        <Code block style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
          {check.details}
        </Code>
      </Property>
      <Property label="Ответ перевозчика">{carrierStateLabel(check.carrierState)}</Property>
      <ResultComparison
        left={{
          title: 'У перевозчика',
          result,
          empty: check.carrierState === null ? 'Нет ответа' : 'Итога нет',
          extra: result && (
            <>
              <Property label="Время у перевозчика">{formatInstant(result.occurredAt, timeZone)}</Property>
              <Text size="xs" c="dimmed" style={{ overflowWrap: 'anywhere' }}>
                Последовательность {result.sequence} · сообщение {result.messageId}
              </Text>
            </>
          ),
        }}
        right={{ title: 'У нас', result: check.localResult, empty: 'Итога нет' }}
        compare={check.carrierState !== null}
      />
    </Stack>
  );
}
