import { Group, Text } from '@mantine/core';
import { IconAlertTriangle } from '@tabler/icons-react';
import { formatDate, formatShortDate } from '../format';

/** The deadline; an overdue one is red, with an icon and the word "просрочена" (not colour alone). */
export function Deadline({ date, overdue, short = false }: { date: string; overdue: boolean; short?: boolean }) {
  const text = short ? `до ${formatShortDate(date)}` : formatDate(date);
  if (!overdue) {
    return <Text size="md">{text}</Text>;
  }
  return (
    <Group gap="xs" wrap="nowrap" c="red">
      <IconAlertTriangle size="1em" aria-hidden />
      <Text size="md" c="red" span>
        {text}
      </Text>
      <Text size="sm" c="red" span>
        · просрочена
      </Text>
    </Group>
  );
}
