import { Badge, Group, SimpleGrid, Stack, Text } from '@mantine/core';
import { formatInstant, formatPrice } from '@/requests/format';
import type { CurrentResult, ProblemMessage } from '@/shared/api';
import { useBusinessTimezone } from '@/shared/system';
import { Property, Section } from '@/shared/ui/parts';
import { OurResult, ourResultRows } from './OurResult';
import { resultStatusLabel } from './resultStatus';

type Row = { label: string; carrier: string | null; ours: string | null };

/**
 * A conflict of two results: the carrier's version and the result the request has now, side by side (one under the
 * other on phones). Rows that differ are marked with a word, not only a colour. The carrier's side is a feed record
 * (`RESULT_CONFLICT`) or its latest answer to reconciliation (`RECONCILIATION_CONFLICT`), which may have no result.
 */
export function ConflictView({ message, current }: { message: ProblemMessage | null; current: CurrentResult | null }) {
  const timeZone = useBusinessTimezone();
  const carrierValues: (string | null)[] = [
    message?.status ? resultStatusLabel(message.status) : null,
    message?.trackingNumber ?? null,
    message?.price == null ? null : formatPrice(message.price),
    message?.reason ?? null,
  ];
  const rows: Row[] = ourResultRows(current)
    .map((row, index) => ({ label: row.label, carrier: carrierValues[index] ?? null, ours: row.value }))
    .filter((row) => row.carrier !== null || row.ours !== null);
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
      <OurResult current={current} rows={rows.map((row) => ({ label: row.label, value: row.ours }))} />
    </SimpleGrid>
  );
}
