import { Badge, Group, SimpleGrid, Stack, Text } from '@mantine/core';
import type { ReactNode } from 'react';
import { Property, Section } from '@/shared/ui/parts';
import { compareResults, type ResultFields } from './comparison';

export type ComparedSide = {
  title: string;
  result: ResultFields | null;
  /** Said instead of the fields when this side has no result. */
  empty: string;
  /** A remark under the title, e.g. that this side is not of the same moment. */
  note?: string;
  /** More properties under the fields (the carrier's time). */
  extra?: ReactNode;
};

/**
 * Two results side by side (one under the other on phones): the carrier's on the left, ours on the right. Fields that
 * differ are marked on the left with a word, not only a colour. Used by a problem's conflict and by a reconciliation
 * check, so both read the same.
 */
export function ResultComparison({
  left,
  right,
  compare = true,
}: {
  left: ComparedSide;
  right: ComparedSide;
  /** `false` when the two sides are not of the same moment: nothing is marked as different. */
  compare?: boolean;
}) {
  const rows = compareResults(left.result, right.result);

  return (
    <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="lg">
      <Column side={left} values={rows.map((row) => ({ label: row.label, value: row.left, differs: compare && row.differs }))} />
      <Column side={right} values={rows.map((row) => ({ label: row.label, value: row.right, differs: false }))} />
    </SimpleGrid>
  );
}

function Column({
  side,
  values,
}: {
  side: ComparedSide;
  values: { label: string; value: string | null; differs: boolean }[];
}) {
  return (
    <Section title={side.title}>
      <Stack gap="xs">
        {side.note && (
          <Text size="xs" c="dimmed">
            {side.note}
          </Text>
        )}
        {side.result ? (
          values.map((row) => (
            <Property key={row.label} label={row.label}>
              <Group gap="xs" wrap="wrap">
                <Text component="span" style={{ overflowWrap: 'anywhere' }}>
                  {row.value ?? '—'}
                </Text>
                {row.differs && (
                  <Badge color="red" variant="light" size="sm" tt="none">
                    отличается
                  </Badge>
                )}
              </Group>
            </Property>
          ))
        ) : (
          <Text c="dimmed">{side.empty}</Text>
        )}
        {side.extra}
      </Stack>
    </Section>
  );
}
