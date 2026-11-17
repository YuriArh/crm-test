import { Button, Drawer, Group, MultiSelect, SegmentedControl, Stack } from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import { IconAdjustmentsHorizontal } from '@tabler/icons-react';
import type { ProblemKind } from '@/shared/api';
import { KIND_VIEWS } from '../labels';
import { activeFilterCount, PROBLEM_KINDS, statusOf, type ProblemListSearch, type StatusFilter } from './search';

type Props = {
  search: ProblemListSearch;
  onStatus: (status: StatusFilter) => void;
  onKinds: (kinds: ProblemKind[]) => void;
  onReset: () => void;
};

const STATUS_OPTIONS = [
  { value: 'open', label: 'Открытые' },
  { value: 'resolved', label: 'Закрытые' },
  { value: 'all', label: 'Все' },
];

const KIND_OPTIONS = PROBLEM_KINDS.map((kind) => ({ value: kind, label: KIND_VIEWS[kind].label }));

function Fields({ search, onStatus, onKinds, vertical }: Omit<Props, 'onReset'> & { vertical?: boolean }) {
  return (
    <>
      <SegmentedControl
        aria-label="Статус"
        data={STATUS_OPTIONS}
        value={statusOf(search)}
        onChange={(value) => onStatus(value as StatusFilter)}
        fullWidth={vertical}
      />
      <MultiSelect
        label="Вид"
        labelProps={vertical ? undefined : { className: 'mantine-visually-hidden' }}
        placeholder={search.kind ? undefined : 'Все виды'}
        data={KIND_OPTIONS}
        value={search.kind ?? []}
        onChange={(value) => onKinds(value as ProblemKind[])}
        clearable
        miw={vertical ? undefined : '16rem'}
        comboboxProps={{ withinPortal: !vertical }}
      />
    </>
  );
}

/** Filters on a desktop: status and kinds in one row, «Сбросить» while something narrows the list. */
export function ProblemFilterBar(props: Props) {
  return (
    <Group gap="sm" wrap="wrap" align="center">
      <Fields {...props} />
      {activeFilterCount(props.search) > 0 && (
        <Button variant="subtle" size="xs" onClick={props.onReset}>
          Сбросить
        </Button>
      )}
    </Group>
  );
}

/** Filters on a phone: a button with the count opens a bottom sheet, as in the registry. */
export function ProblemFilterSheet(props: Props) {
  const [opened, { open, close }] = useDisclosure(false);
  const count = activeFilterCount(props.search);
  return (
    <>
      <Button variant="default" leftSection={<IconAdjustmentsHorizontal size="1em" />} onClick={open}>
        {count > 0 ? `Фильтры · ${count}` : 'Фильтры'}
      </Button>
      <Drawer opened={opened} onClose={close} position="bottom" title="Фильтры" size="auto">
        <Stack gap="md" pb="md">
          <Fields {...props} vertical />
          <Group grow>
            <Button variant="default" onClick={props.onReset} disabled={count === 0}>
              Сбросить
            </Button>
            <Button onClick={close}>Готово</Button>
          </Group>
        </Stack>
      </Drawer>
    </>
  );
}
