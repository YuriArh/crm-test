import { Badge, Group, SimpleGrid, Stack, Text } from '@mantine/core';
import { formatInstant, formatPrice } from '@/requests/format';
import type { CurrentResult, ProblemMessage } from '@/shared/api';
import { useBusinessTimezone } from '@/shared/system';
import { Property, Section } from '@/shared/ui/parts';
import { resultStatusLabel } from './resultStatus';

type Row = { label: string; carrier: string | null; ours: string | null };

/**
 * A conflict of two results: the carrier's version and the result the request has now, side by side (one under the
 * other on phones). Rows that differ are marked with a word, not only a colour.
 */
export function ConflictView({ message, current }: { message: ProblemMessage | null; current: CurrentResult | null }) {
  const timeZone = useBusinessTimezone();
  const result = current?.result ?? null;
  const price = (value: number | null | undefined) => (value === null || value === undefined ? null : formatPrice(value));
  const rows: Row[] = [
    {
      label: 'Статус',
      carrier: message?.status ? resultStatusLabel(message.status) : null,
      ours: current ? resultStatusLabel(current.businessStatus) : null,
    },
    { label: 'Номер отправления', carrier: message?.trackingNumber ?? null, ours: result?.trackingNumber ?? null },
    { label: 'Стоимость', carrier: price(message?.price), ours: price(result?.price) },
    { label: 'Причина отказа', carrier: message?.reason ?? null, ours: result?.rejectionReason ?? null },
  ].filter((row) => row.carrier !== null || row.ours !== null);
  const carrierHasResult = message !== null && message.status !== null;

  return (
    <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="lg">
      <Section title="Версия перевозчика">
        {carrierHasResult ? (
          <Stack gap="xs">
            {rows.map((row) => (
              <Property key={row.label} label={row.label}>
                <Group gap="xs" wrap="wrap">
                  <Text component="span" style={{ overflowWrap: 'anywhere' }}>
                    {row.carrier ?? '—'}
                  </Text>
                  {row.carrier !== row.ours && (
                    <Badge color="red" variant="light" size="sm" tt="none">
                      отличается
                    </Badge>
                  )}
                </Group>
              </Property>
            ))}
            {message.occurredAt && (
              <Property label="Время у перевозчика">{formatInstant(message.occurredAt, timeZone)}</Property>
            )}
          </Stack>
        ) : (
          <Text c="dimmed">У перевозчика итога ещё нет</Text>
        )}
      </Section>
      <Section title="Наш итог">
        {current ? (
          <Stack gap="xs">
            {rows.map((row) => (
              <Property key={row.label} label={row.label}>
                <Text component="span" style={{ overflowWrap: 'anywhere' }}>
                  {row.ours ?? '—'}
                </Text>
              </Property>
            ))}
            {result?.resultAt && <Property label="Получен">{formatInstant(result.resultAt, timeZone)}</Property>}
          </Stack>
        ) : (
          <Text c="dimmed">Итога нет</Text>
        )}
      </Section>
    </SimpleGrid>
  );
}
