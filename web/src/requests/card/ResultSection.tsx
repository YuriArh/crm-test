import { Group, Stack, Text } from '@mantine/core';
import type { RequestCard } from '@/shared/api';
import { useBusinessTimezone } from '@/shared/system';
import { formatInstant, formatPrice } from '../format';
import { CopyValue, Property, Section } from './parts';

/** The carrier's final answer: tracking number and price, or the reason of a rejection. */
export function ResultSection({ card }: { card: RequestCard }) {
  const timeZone = useBusinessTimezone();
  const { result } = card;
  const resultAt = result?.resultAt ? formatInstant(result.resultAt, timeZone) : null;

  if (card.businessStatus === 'SUCCEEDED' && result) {
    return (
      <Section title="Результат">
        <Group gap="xl" align="flex-start" wrap="wrap">
          {result.trackingNumber && (
            <Property label="Номер отправления">
              <CopyValue value={result.trackingNumber} label="номер отправления" mono />
            </Property>
          )}
          {result.price !== null && <Property label="Стоимость">{formatPrice(result.price)}</Property>}
          {resultAt && <Property label="Получен">{resultAt}</Property>}
        </Group>
      </Section>
    );
  }

  if (card.businessStatus === 'REJECTED' && result) {
    return (
      <Section title="Результат">
        <Stack gap="xs">
          <Property label="Причина отказа">
            <Text c="red" style={{ overflowWrap: 'anywhere' }}>
              {result.rejectionReason ?? 'Причина не указана'}
            </Text>
          </Property>
          {resultAt && <Property label="Получен">{resultAt}</Property>}
        </Stack>
      </Section>
    );
  }

  return (
    <Section title="Результат">
      <Text c="dimmed">Результата пока нет</Text>
    </Section>
  );
}
