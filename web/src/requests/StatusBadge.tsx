import { Badge } from '@mantine/core';
import type { StatusView } from './statuses';

/** A status as a word with a coloured dot; the colour only repeats the word. */
export function StatusBadge({ view, size = 'md' }: { view: StatusView; size?: 'sm' | 'md' }) {
  return (
    <Badge variant="dot" color={view.color} size={size} radius="sm" tt="none" fw={510}>
      {view.label}
    </Badge>
  );
}
