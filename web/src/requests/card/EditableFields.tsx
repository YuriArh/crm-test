import { Button, Group, Select, Text, Textarea } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import type { RequestCard } from '@/shared/api';
import { operatorsQueryOptions } from './api';
import { COMMENT_MAX_LENGTH } from './edit';
import { Section } from './parts';
import { NO_ASSIGNEE } from './PropertiesPanel';
import type { CardEditor } from './useCardEditor';

/** The option of "nobody"; Select needs a string value for it. */
const UNASSIGNED = '';

/** The counter appears close to the limit only. */
const COUNTER_FROM = 1800;

/** The comment: anyone who can see the request may change it; an empty one removes it. */
export function CommentField({ editor }: { editor: CardEditor }) {
  const value = editor.value('comment') ?? '';

  return (
    <Section title="Комментарий">
      <Textarea
        aria-label="Комментарий"
        placeholder="Нет комментария"
        autosize
        minRows={3}
        maxRows={12}
        maxLength={COMMENT_MAX_LENGTH}
        value={value}
        readOnly={editor.saving}
        onChange={(event) => editor.change('comment', event.currentTarget.value)}
        description={
          value.length >= COUNTER_FROM ? `${value.length} / ${COMMENT_MAX_LENGTH}` : undefined
        }
        inputWrapperOrder={['label', 'input', 'description', 'error']}
      />
    </Section>
  );
}

/**
 * The assignee, for operators of the request's service: their service's operators and «Не назначен».
 * The current assignee stays a choice even when no longer in the list (e.g. disabled meanwhile).
 */
export function AssigneeField({ card, editor }: { card: RequestCard; editor: CardEditor }) {
  const operators = useQuery(operatorsQueryOptions);
  const value = editor.value('assigneeId');

  const options = [{ value: UNASSIGNED, label: NO_ASSIGNEE }];
  for (const operator of operators.data ?? []) {
    options.push({ value: operator.id, label: operator.displayName });
  }
  if (card.assignee && !options.some((option) => option.value === card.assignee?.id)) {
    options.push({ value: card.assignee.id, label: card.assignee.displayName });
  }

  return (
    <Select
      label="Ответственный"
      data={options}
      value={value ?? UNASSIGNED}
      onChange={(next) => editor.change('assigneeId', next === null || next === UNASSIGNED ? null : next)}
      allowDeselect={false}
      readOnly={editor.saving}
      loading={operators.isPending}
      error={editor.fieldErrors.assigneeId}
      comboboxProps={{ withinPortal: true }}
    />
  );
}

/** «Отменить» and «Сохранить», only while there are changes; the error of the last save above them. */
export function SaveActions({ editor }: { editor: CardEditor }) {
  if (!editor.dirty && editor.error === null) return null;

  return (
    <Group justify="flex-end" gap="sm" wrap="wrap">
      {editor.error && (
        <Text c="red" size="sm" role="alert" mr="auto">
          {editor.error}
        </Text>
      )}
      {editor.dirty && (
        <>
          <Button variant="default" onClick={editor.cancel} disabled={editor.saving}>
            Отменить
          </Button>
          <Button onClick={() => void editor.save()} loading={editor.saving}>
            Сохранить
          </Button>
        </>
      )}
    </Group>
  );
}
