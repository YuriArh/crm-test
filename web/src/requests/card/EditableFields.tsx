import { Alert, Button, Group, Paper, Select, Stack, Text, Textarea } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import type { RequestCard } from '@/shared/api';
import { operatorsQueryOptions } from './api';
import { COMMENT_MAX_LENGTH, type EditableField } from './edit';
import { Section } from './parts';
import { NO_ASSIGNEE } from './PropertiesPanel';
import type { CardEditor } from './useCardEditor';

/** The option of "nobody"; Select needs a string value for it. */
const UNASSIGNED = '';

/** The counter appears close to the limit only. */
const COUNTER_FROM = 1800;

/** The comment: anyone who can see the request may change it; an empty one removes it. */
export function CommentField({ card, editor }: { card: RequestCard; editor: CardEditor }) {
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
        description={value.length >= COUNTER_FROM ? `${value.length} / ${COMMENT_MAX_LENGTH}` : undefined}
        inputWrapperOrder={['label', 'input', 'description', 'error']}
      />
      <ConflictNote field="comment" theirs={card.comment ?? 'Нет комментария'} editor={editor} />
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
    <Stack gap="xs">
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
        // The label looks like the other property labels of the panel.
        styles={{ label: { color: 'var(--mantine-color-dimmed)', fontWeight: 400 } }}
      />
      <ConflictNote field="assigneeId" theirs={card.assignee?.displayName ?? NO_ASSIGNEE} editor={editor} />
    </Stack>
  );
}

/** Someone else changed the field being edited: show their value and let the user choose. */
function ConflictNote({ field, theirs, editor }: { field: EditableField; theirs: string; editor: CardEditor }) {
  if (!editor.conflicts.includes(field)) return null;

  return (
    <Alert color="yellow" variant="light" p="sm" role="status">
      <Stack gap="xs">
        <Text size="sm">
          Изменил другой пользователь:{' '}
          <Text component="span" size="sm" fw={510} style={{ overflowWrap: 'anywhere' }}>
            {theirs}
          </Text>
        </Text>
        <Group gap="xs">
          <Button size="xs" onClick={() => void editor.keepMine(field)} loading={editor.saving}>
            Оставить моё
          </Button>
          <Button size="xs" variant="default" onClick={() => editor.takeTheirs(field)} disabled={editor.saving}>
            Взять их
          </Button>
        </Group>
      </Stack>
    </Alert>
  );
}

/**
 * «Отменить» and «Сохранить», only while there are changes, with the error of the last save. `inline` sits
 * under the comment (desktop); `bar` sticks to the bottom of the screen (phone).
 */
export function SaveActions({ editor, variant }: { editor: CardEditor; variant: 'inline' | 'bar' }) {
  if (!editor.dirty && editor.error === null) return null;

  const actions = <SaveButtons editor={editor} />;
  if (variant === 'inline') return actions;
  return (
    <Paper
      withBorder
      shadow="md"
      p="sm"
      pos="sticky"
      bottom="var(--mantine-spacing-sm)"
      bg="var(--app-color-surface-raised)"
      style={{ zIndex: 'var(--mantine-z-index-app)' }}
      aria-label="Несохранённые изменения"
      role="region"
    >
      {actions}
    </Paper>
  );
}

function SaveButtons({ editor }: { editor: CardEditor }) {
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
          <Button onClick={() => void editor.save()} loading={editor.saving} disabled={editor.conflicts.length > 0}>
            Сохранить
          </Button>
        </>
      )}
    </Group>
  );
}
