import { notifications } from '@mantine/notifications';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { isApiError, type RequestCard } from '@/shared/api';
import { requestKeys } from '../keys';
import { isCardGone, operatorsQueryOptions, requestCardQueryOptions, useUpdateRequest } from './api';
import { conflictingFields, keepMine as keepMineIn, resolveConflict, takeTheirs as takeTheirsIn } from './conflict';
import { displayValue, editField, patchBody, type EditableField, type EditState, type FieldValue } from './edit';

export const SAVE_FAILED = 'Не удалось сохранить, повторите';
export const FORBIDDEN = 'Недостаточно прав для этого изменения';
export const INVALID_ASSIGNEE = 'Этот оператор недоступен';

/** Silent resends after `409` in one save; more means the request keeps changing under the user. */
const MAX_RESENDS = 3;

export type CardEditor = {
  /** The draft, `null` when nothing is changed. */
  edit: EditState | null;
  dirty: boolean;
  saving: boolean;
  /** Edited fields someone else has changed meanwhile; saving waits for the user's decision on them. */
  conflicts: EditableField[];
  /** An error of the last save that is not about one field. */
  error: string | null;
  /** Errors of the last save about one field. */
  fieldErrors: Partial<Record<EditableField, string>>;
  value: (field: EditableField) => FieldValue;
  change: (field: EditableField, value: FieldValue) => void;
  cancel: () => void;
  save: () => Promise<void>;
  /** «Оставить моё»: keep the user's value over theirs and save it on the new version. */
  keepMine: (field: EditableField) => Promise<void>;
  /** «Взять их»: drop the user's value of the field. */
  takeTheirs: (field: EditableField) => void;
};

/**
 * The edit of the comment and the assignee. The draft lives here, apart from the card data, so the
 * 5-second refresh never overwrites it; a refresh that brings someone else's value of an edited field
 * shows the conflict at once (`conflicts` is derived from the latest card). `PATCH` sends only the changed
 * fields and the version the edit started from; `409` is resolved by {@link resolveConflict}.
 */
export function useCardEditor(card: RequestCard): CardEditor {
  const queryClient = useQueryClient();
  const update = useUpdateRequest(card.id);
  const [edit, setEdit] = useState<EditState | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Partial<Record<EditableField, string>>>({});
  // Covers the whole save: the PATCH, a re-read after 409 and the silent resends.
  const [sending, setSending] = useState(false);
  const conflicts = conflictingFields(edit, card);

  const clearErrors = () => {
    setError(null);
    setFieldErrors({});
  };

  const change = (field: EditableField, value: FieldValue) => {
    setEdit((state) => editField(state, card, field, value));
    setError(null);
    setFieldErrors((errors) => {
      const { [field]: _, ...rest } = errors;
      return rest;
    });
  };

  const cancel = () => {
    setEdit(null);
    clearErrors();
  };

  /** The card of a `409`: the one in the answer, or a fresh read when the server sent none. */
  const currentAfterConflict = async (failure: unknown): Promise<RequestCard> => {
    const current = isApiError(failure) ? failure.current : undefined;
    if (current) {
      queryClient.setQueryData(requestKeys.card(card.id), current);
      return current;
    }
    return queryClient.fetchQuery({ ...requestCardQueryOptions(card.id), staleTime: 0 });
  };

  const done = () => {
    setEdit(null);
    notifications.show({ message: 'Сохранено', color: 'green', autoClose: 2000 });
  };

  const send = async (start: EditState) => {
    clearErrors();
    setSending(true);
    try {
      await sendUntilSettled(start);
    } finally {
      setSending(false);
    }
  };

  const sendUntilSettled = async (start: EditState) => {
    let state = start;
    for (let resends = 0; ; resends += 1) {
      try {
        await update.mutateAsync(patchBody(state));
        done();
        return;
      } catch (failure) {
        if (!isApiError(failure, 'VERSION_CONFLICT') || resends >= MAX_RESENDS) {
          await reportFailure(failure);
          return;
        }
        let current: RequestCard;
        try {
          current = await currentAfterConflict(failure);
        } catch (readFailure) {
          await reportFailure(readFailure);
          return;
        }
        const outcome = resolveConflict(state, current);
        if (outcome.kind === 'settled') {
          done();
          return;
        }
        if (outcome.kind === 'conflict') {
          // The cache now holds their values, so `conflicts` shows them under the fields.
          setEdit(state);
          return;
        }
        state = outcome.state;
        setEdit(state);
      }
    }
  };

  const reportFailure = async (failure: unknown) => {
    if (isCardGone(failure)) {
      // Out of the caller's scope now: the refresh answers 404 and the page says "not found".
      await queryClient.invalidateQueries({ queryKey: requestKeys.card(card.id) });
    } else if (isApiError(failure, 'INVALID_ASSIGNEE') || (isApiError(failure) && failure.status === 422)) {
      // The chosen operator was disabled or moved meanwhile: the list is out of date.
      setFieldErrors({ assigneeId: INVALID_ASSIGNEE });
      void queryClient.invalidateQueries({ queryKey: operatorsQueryOptions.queryKey });
    } else if (isApiError(failure, 'FORBIDDEN')) {
      setError(FORBIDDEN);
    } else if (!isApiError(failure, 'UNAUTHENTICATED')) {
      setError(SAVE_FAILED);
    }
  };

  const save = async () => {
    if (edit === null || conflicts.length > 0) return;
    await send(edit);
  };

  const keepMine = async (field: EditableField) => {
    if (edit === null) return;
    const next = keepMineIn(edit, card, field);
    setEdit(next);
    if (next !== null && conflictingFields(next, card).length === 0) await send(next);
  };

  const takeTheirs = (field: EditableField) => {
    if (edit === null) return;
    setEdit(takeTheirsIn(edit, card, field));
  };

  return {
    edit,
    dirty: edit !== null,
    saving: sending,
    conflicts,
    error,
    fieldErrors,
    value: (field) => displayValue(edit, card, field),
    change,
    cancel,
    save,
    keepMine,
    takeTheirs,
  };
}
