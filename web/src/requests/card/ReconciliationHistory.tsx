import { Accordion, Badge, Code, Group, Stack, Text } from '@mantine/core';
import { resultStatusLabel } from '@/problems/details/resultStatus';
import type { Reconciliation } from '@/shared/api';
import { useBusinessTimezone } from '@/shared/system';
import { Property, Section } from '@/shared/ui/parts';
import { formatInstant, formatPrice } from '../format';
import { carrierStateLabel, OUTCOME_VIEWS, triggerLabel } from './reconciliation';

/**
 * «Сверки» — the latest reconciliation checks of the request, newest first, as dispatch sends them (web-problems.md
 * §8.3). The row shows the details already: a red `CONFLICT` with no problem opened («decided earlier») explains
 * itself without opening. Rows open from the keyboard (they are the accordion's buttons).
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

function CheckDetails({ check, timeZone }: { check: Reconciliation; timeZone: string }) {
  const result = check.carrierResult;
  return (
    <Stack gap="xs">
      <Property label="Подробности">
        <Code block style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
          {check.details}
        </Code>
      </Property>
      <Property label="Ответ перевозчика">{carrierStateLabel(check.carrierState)}</Property>
      {result && (
        <>
          <Property label="Итог у перевозчика">{resultStatusLabel(result.status)}</Property>
          {result.trackingNumber && <Property label="Номер отправления">{result.trackingNumber}</Property>}
          {result.price !== null && <Property label="Стоимость">{formatPrice(result.price)}</Property>}
          {result.reason && (
            <Property label="Причина отказа">
              <Text component="span" style={{ overflowWrap: 'anywhere' }}>
                {result.reason}
              </Text>
            </Property>
          )}
          <Property label="Время у перевозчика">{formatInstant(result.occurredAt, timeZone)}</Property>
          <Text size="xs" c="dimmed" style={{ overflowWrap: 'anywhere' }}>
            Последовательность {result.sequence} · сообщение {result.messageId}
          </Text>
        </>
      )}
    </Stack>
  );
}
