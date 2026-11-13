import { Alert, Button, Group, List, Select, Stack, Text, Textarea } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';

import { describeError, isApiError } from '../api/errors';
import type { RequestCard, UpdateRequest } from '../api/types';
import { fetchCard, requestKeys, useOperators, useUpdateRequest } from './api';

const MAX_COMMENT = 2000;

interface Editable {
  comment: string;
  assigneeId: string | null;
}

type Field = keyof Editable;

/** What the user started from (with its version) and what they have typed since. */
interface Edit {
  base: Editable & { version: number };
  draft: Editable;
}

interface Conflict {
  /** Fields the user changed that someone else has changed too. */
  fields: Field[];
  current: RequestCard;
  /** The user's draft on top of the current version, ready to be sent again. */
  rebased: Edit;
}

function editableOf(card: RequestCard): Editable {
  return { comment: card.comment ?? '', assigneeId: card.assignee?.id ?? null };
}

/** The server stores the comment stripped, and an empty one as `null`. */
function normalizeComment(comment: string): string | null {
  const text = comment.trim();
  return text === '' ? null : text;
}

function same(field: Field, a: Editable, b: Editable): boolean {
  return field === 'comment'
    ? normalizeComment(a.comment) === normalizeComment(b.comment)
    : a.assigneeId === b.assigneeId;
}

function changedFields(edit: Edit): Field[] {
  return (['assigneeId', 'comment'] as const).filter((field) => !same(field, edit.draft, edit.base));
}

/**
 * Comment (anyone who sees the request) and assignee (operators of its service). Sends the version the
 * user started from. On `VERSION_CONFLICT` nothing is overwritten silently: if someone else changed the
 * same fields, the user sees both values and decides; if only other data changed (for example the
 * status, when a result arrived), the user's change cannot erase anyone's input and is sent again on top
 * of the current version.
 */
export function RequestEditPanel({ card, canAssign }: { card: RequestCard; canAssign: boolean }) {
  const queryClient = useQueryClient();
  const update = useUpdateRequest(card.id);
  const operators = useOperators(canAssign);
  const [edit, setEdit] = useState<Edit | null>(null);
  const [conflict, setConflict] = useState<Conflict | null>(null);
  /** The conflict answer had no current card and loading it failed. */
  const [reloadError, setReloadError] = useState<unknown>(null);

  const values = edit?.draft ?? editableOf(card);
  const dirty = edit !== null && changedFields(edit).length > 0;

  const change = (patch: Partial<Editable>) =>
    setEdit((previous) =>
      previous
        ? { ...previous, draft: { ...previous.draft, ...patch } }
        : { base: { ...editableOf(card), version: card.version }, draft: { ...editableOf(card), ...patch } },
    );

  const reset = () => {
    setEdit(null);
    setConflict(null);
    setReloadError(null);
    update.reset();
  };

  const save = (target: Edit, autoRetry: boolean) => {
    const fields = changedFields(target);
    if (fields.length === 0) {
      reset();
      return;
    }
    const body: UpdateRequest = { version: target.base.version };
    if (fields.includes('comment')) body.comment = normalizeComment(target.draft.comment);
    if (fields.includes('assigneeId')) body.assigneeId = target.draft.assigneeId;

    setConflict(null);
    setReloadError(null);
    update.mutate(body, {
      onSuccess: () => {
        setEdit(null);
        notifications.show({ color: 'green', message: 'Изменения сохранены' });
      },
      onError: async (error) => {
        if (!isApiError(error, 'VERSION_CONFLICT')) {
          return;
        }
        let current = error.current;
        if (!current) {
          try {
            current = await fetchCard(card.id);
          } catch (loadError) {
            setReloadError(loadError);
            return;
          }
          queryClient.setQueryData(requestKeys.card(card.id), current);
        }
        const currentValues = editableOf(current);
        // Only the fields this user changed carry their value over; every other field takes the current
        // value, so re-sending can never revert someone else's change to a field this user did not touch.
        const draft: Editable = { ...currentValues };
        for (const field of fields) {
          if (field === 'comment') draft.comment = target.draft.comment;
          else draft.assigneeId = target.draft.assigneeId;
        }
        const rebased: Edit = { base: { ...currentValues, version: current.version }, draft };
        const touchedByOthers = fields.filter((field) => !same(field, currentValues, target.base));
        setEdit(rebased);
        if (touchedByOthers.length === 0 && autoRetry) {
          save(rebased, false);
        } else {
          setConflict({ fields: touchedByOthers.length > 0 ? touchedByOthers : fields, current, rebased });
        }
      },
    });
  };

  const operatorName = (id: string | null) =>
    id === null
      ? 'не назначен'
      : (operators.data?.find((operator) => operator.id === id)?.displayName ?? 'другой оператор');

  const showError = update.isError && !isApiError(update.error, 'VERSION_CONFLICT');

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        if (edit) save(edit, true);
      }}
    >
      <Stack gap="sm">
        {conflict && (
          <Alert color="yellow" title="Заявку изменил другой пользователь" role="alert">
            <Stack gap="xs">
              <List size="sm" spacing={4}>
                {conflict.fields.includes('assigneeId') && (
                  <List.Item>
                    Исполнитель: сейчас — {conflict.current.assignee?.displayName ?? 'не назначен'}, ваш
                    вариант — {operatorName(conflict.rebased.draft.assigneeId)}
                  </List.Item>
                )}
                {conflict.fields.includes('comment') && (
                  <List.Item>
                    Комментарий: сейчас — «{conflict.current.comment ?? ''}», ваш вариант — «
                    {normalizeComment(conflict.rebased.draft.comment) ?? ''}»
                  </List.Item>
                )}
              </List>
              <Group gap="xs">
                <Button
                  size="xs"
                  color="yellow"
                  onClick={() => save(conflict.rebased, false)}
                  loading={update.isPending}
                >
                  Сохранить мой вариант
                </Button>
                <Button size="xs" variant="default" onClick={reset}>
                  Оставить текущий
                </Button>
              </Group>
            </Stack>
          </Alert>
        )}
        {reloadError !== null && (
          <Alert color="red" role="alert" title="Заявку изменил другой пользователь">
            Не удалось загрузить её новую версию: {describeError(reloadError)} Обновите страницу.
          </Alert>
        )}
        {showError && (
          <Alert color="red" role="alert">
            {isApiError(update.error, 'INVALID_ASSIGNEE')
              ? 'Исполнителем можно назначить только оператора вашей службы.'
              : describeError(update.error)}
          </Alert>
        )}
        {canAssign ? (
          <Select
            label="Исполнитель"
            placeholder={operators.isPending ? 'Загрузка…' : 'Не назначен'}
            data={(operators.data ?? []).map((operator) => ({
              value: operator.id,
              label: operator.displayName,
            }))}
            value={values.assigneeId}
            onChange={(value) => change({ assigneeId: value })}
            clearable
            disabled={operators.isError}
            error={operators.isError ? 'Не удалось загрузить список операторов' : undefined}
          />
        ) : (
          <div>
            <Text size="sm" fw={500}>
              Исполнитель
            </Text>
            <Text size="sm" c={card.assignee ? undefined : 'dimmed'}>
              {card.assignee?.displayName ?? 'не назначен'}
            </Text>
          </div>
        )}
        <Textarea
          label="Комментарий"
          autosize
          minRows={3}
          maxRows={10}
          maxLength={MAX_COMMENT}
          value={values.comment}
          onChange={(event) => change({ comment: event.currentTarget.value })}
          description={`${values.comment.length} / ${MAX_COMMENT}`}
        />
        <Group justify="flex-end" gap="xs">
          {edit && (
            <Button variant="default" onClick={reset} disabled={update.isPending}>
              Отменить
            </Button>
          )}
          <Button type="submit" disabled={!dirty || conflict !== null} loading={update.isPending}>
            Сохранить
          </Button>
        </Group>
      </Stack>
    </form>
  );
}
