import { Stack, Text } from '@mantine/core';
import type { ReactNode } from 'react';
import type { RequestCard } from '@/shared/api';
import { useBusinessTimezone } from '@/shared/system';
import { formatInstant } from '../format';
import { Property, Section } from './parts';

export const NO_ASSIGNEE = 'Не назначен';

/** Who and when: assignee, author, created, updated. `assignee` replaces the plain name with an editor. */
export function PropertiesPanel({ card, assignee }: { card: RequestCard; assignee?: ReactNode }) {
  const timeZone = useBusinessTimezone();

  return (
    <Section title="Свойства">
      <Stack gap="sm">
        {assignee ?? (
          <Property label="Ответственный">
            {card.assignee ? card.assignee.displayName : <Text c="dimmed">{NO_ASSIGNEE}</Text>}
          </Property>
        )}
        <Property label="Автор">{card.owner.displayName}</Property>
        <Property label="Создана">{formatInstant(card.createdAt, timeZone)}</Property>
        <Property label="Изменена">{formatInstant(card.updatedAt, timeZone)}</Property>
      </Stack>
    </Section>
  );
}
