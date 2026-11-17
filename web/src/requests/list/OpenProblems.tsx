import { Badge } from '@mantine/core';
import { IconAlertTriangle } from '@tabler/icons-react';

/**
 * The number of a request's open problems in the registry (operators only: initiators get `null`). Nothing at 0,
 * so the usual row stays quiet; the number is also said in words for screen readers.
 */
export function OpenProblems({ count }: { count: number | null }) {
  if (!count) return null;
  return (
    <Badge
      color="orange"
      variant="light"
      size="sm"
      radius="sm"
      leftSection={<IconAlertTriangle size="0.9em" />}
      aria-label={`Открытых проблем: ${count}`}
    >
      {count}
    </Badge>
  );
}
