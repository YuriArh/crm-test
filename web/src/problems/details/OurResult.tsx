import { Stack, Text } from '@mantine/core';
import { formatInstant, formatPrice } from '@/requests/format';
import type { CurrentResult } from '@/shared/api';
import { useBusinessTimezone } from '@/shared/system';
import { Property, Section } from '@/shared/ui/parts';
import { resultStatusLabel } from './resultStatus';

export type ResultRow = { label: string; value: string | null };

/** The fields of our result in the order both sides of a conflict show them; empty ones are `null`. */
export function ourResultRows(current: CurrentResult | null): ResultRow[] {
  const result = current?.result ?? null;
  return [
    { label: 'Статус', value: current ? resultStatusLabel(current.businessStatus) : null },
    { label: 'Номер отправления', value: result?.trackingNumber ?? null },
    { label: 'Стоимость', value: result?.price == null ? null : formatPrice(result.price) },
    { label: 'Причина отказа', value: result?.rejectionReason ?? null },
  ];
}

/**
 * «Наш итог» — the result the request has now: the right column of a conflict, and alone for a lost operation
 * (`EXTERNAL_MISSING`), where it tells whether a retry is still needed. `rows` are given by a conflict, so both of
 * its columns list the same fields; alone, the empty fields are left out.
 */
export function OurResult({ current, rows }: { current: CurrentResult | null; rows?: ResultRow[] }) {
  const timeZone = useBusinessTimezone();
  const shown = rows ?? ourResultRows(current).filter((row) => row.value !== null);
  const resultAt = current?.result?.resultAt;

  return (
    <Section title="Наш итог">
      {current ? (
        <Stack gap="xs">
          {shown.map((row) => (
            <Property key={row.label} label={row.label}>
              <Text component="span" style={{ overflowWrap: 'anywhere' }}>
                {row.value ?? '—'}
              </Text>
            </Property>
          ))}
          {resultAt && <Property label="Получен">{formatInstant(resultAt, timeZone)}</Property>}
        </Stack>
      ) : (
        <Text c="dimmed">Итога нет</Text>
      )}
    </Section>
  );
}
