import { Group, Stack, Text } from '@mantine/core';
import { IconLock } from '@tabler/icons-react';
import type { RequestCard } from '@/shared/api';
import { formatDate, formatWeight } from '../format';
import { Section } from './parts';

export const IMMUTABLE_HINT = 'Содержимое заявки не меняется после создания';

/** What was ordered: read-only, a request goes to the carrier as soon as it is created (spec §1). */
export function ContentSection({ card }: { card: RequestCard }) {
  return (
    <Section
      title="Содержимое"
      aside={
        <Text component="span" c="dimmed" lh={1} aria-hidden>
          <IconLock size="1em" />
        </Text>
      }
    >
      <Stack gap="xs">
        <Group gap="xs" wrap="wrap">
          <Text size="lg" fw={510} style={{ overflowWrap: 'anywhere' }}>
            {card.cargoDescription}
          </Text>
          <Text size="lg" c="dimmed">
            · {formatWeight(card.weightKg)}
          </Text>
        </Group>
        <Text style={{ overflowWrap: 'anywhere' }}>{card.deliveryAddress}</Text>
        <Text>
          <Text component="span" c="dimmed">
            Срок:
          </Text>{' '}
          {formatDate(card.deadline)}
        </Text>
        <Text size="sm" c="dimmed">
          {IMMUTABLE_HINT}
        </Text>
      </Stack>
    </Section>
  );
}
