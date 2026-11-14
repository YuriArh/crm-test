import { Button, Group, Select, Switch, Text } from '@mantine/core';
import { IconX } from '@tabler/icons-react';
import type { BusinessStatus, DeliveryStatus, Role } from '@/shared/api';
import { BUSINESS_VIEWS, OPERATOR_DELIVERY_VIEWS } from '../statuses';
import classes from './RequestList.module.css';
import {
  BUSINESS_STATUSES,
  DELIVERY_STATUSES,
  SORT_OPTIONS,
  type ListFilters,
  type ListSort,
  type RequestListSearch,
} from './search';

const ALL = 'ALL';

const RESULT_OPTIONS = [
  { value: ALL, label: 'Все' },
  ...BUSINESS_STATUSES.map((status) => ({ value: status, label: BUSINESS_VIEWS[status].label })),
];

const DELIVERY_OPTIONS = [
  { value: ALL, label: 'Все' },
  ...DELIVERY_STATUSES.map((status) => ({ value: status, label: OPERATOR_DELIVERY_VIEWS[status].label })),
];

type FieldsProps = {
  filters: ListFilters;
  role: Role;
  onChange: (change: Partial<ListFilters>) => void;
  /** `inline`: one row with the label inside the field (desktop); `stacked`: labels above (phone drawer). */
  layout: 'inline' | 'stacked';
};

function inlineLabel(text: string, width: string, layout: FieldsProps['layout']) {
  return layout === 'inline'
    ? {
        'aria-label': text,
        leftSection: (
          <Text size="md" c="dimmed">
            {text}:
          </Text>
        ),
        leftSectionWidth: width,
      }
    : { label: text };
}

/** The filter fields, shared by the desktop bar and the phone drawer. Initiators have no delivery filter. */
export function FilterFields({ filters, role, onChange, layout }: FieldsProps) {
  const inline = layout === 'inline';
  return (
    <>
      <Select
        {...inlineLabel('Результат', '6rem', layout)}
        data={RESULT_OPTIONS}
        value={filters.result ?? ALL}
        onChange={(value) =>
          onChange({ result: value && value !== ALL ? (value as BusinessStatus) : undefined })
        }
        allowDeselect={false}
        w={inline ? '15rem' : undefined}
        classNames={{ input: filters.result ? classes.activeFilter : undefined }}
        comboboxProps={{ width: 'max-content', position: 'bottom-start' }}
      />
      {role === 'OPERATOR' && (
        <Select
          {...inlineLabel('Доставка', '5.5rem', layout)}
          data={DELIVERY_OPTIONS}
          value={filters.delivery ?? ALL}
          onChange={(value) =>
            onChange({ delivery: value && value !== ALL ? (value as DeliveryStatus) : undefined })
          }
          allowDeselect={false}
          w={inline ? '17rem' : undefined}
          classNames={{ input: filters.delivery ? classes.activeFilter : undefined }}
          comboboxProps={{ width: 'max-content', position: 'bottom-start' }}
        />
      )}
      <Switch
        label="Только просроченные"
        checked={filters.overdue === 1}
        onChange={(event) => onChange({ overdue: event.currentTarget.checked ? 1 : undefined })}
        size={inline ? 'sm' : 'md'}
      />
    </>
  );
}

/** The sort of the list. */
export function SortSelect({ value, onChange, compact = false }: { value: ListSort; onChange: (sort: ListSort) => void; compact?: boolean }) {
  return (
    <Select
      aria-label="Сортировка"
      data={SORT_OPTIONS.map((option) => ({ ...option }))}
      value={value}
      onChange={(next) => next && onChange(next as ListSort)}
      allowDeselect={false}
      w={compact ? '10.5rem' : '11.5rem'}
      comboboxProps={{ position: 'bottom-end', width: 'max-content' }}
    />
  );
}

type BarProps = {
  search: RequestListSearch;
  role: Role;
  activeCount: number;
  onFilters: (change: Partial<ListFilters>) => void;
  onReset: () => void;
  onSort: (sort: ListSort) => void;
};

/** The desktop filter bar: filters in one row, "Сбросить" while any is active, the sort on the right. */
export function RequestFilterBar({ search, role, activeCount, onFilters, onReset, onSort }: BarProps) {
  return (
    <Group justify="space-between" gap="sm" wrap="nowrap">
      <Group gap="sm">
        <FilterFields filters={search} role={role} onChange={onFilters} layout="inline" />
        {activeCount > 0 && (
          <Button variant="subtle" color="gray" size="xs" leftSection={<IconX size="1em" />} onClick={onReset}>
            Сбросить
          </Button>
        )}
      </Group>
      <SortSelect value={search.sort ?? 'new'} onChange={onSort} />
    </Group>
  );
}
