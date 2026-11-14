import { Button, Group, Text, Textarea } from '@mantine/core';
import { COMMENT_MAX_LENGTH } from './edit';
import { Section } from './parts';
import type { CardEditor } from './useCardEditor';

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
