import { notifications } from '@mantine/notifications';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { isApiError, type RequestCard } from '@/shared/api';
import { requestKeys } from '../keys';
import { isCardGone, operatorsQueryOptions, useUpdateRequest } from './api';
import { displayValue, editField, patchBody, type EditableField, type EditState, type FieldValue } from './edit';

export const SAVE_FAILED = 'Не удалось сохранить, повторите';
export const FORBIDDEN = 'Недостаточно прав для этого изменения';
export const INVALID_ASSIGNEE = 'Этот оператор недоступен';

export type CardEditor = {
  /** The draft, `null` when nothing is changed. */
  edit: EditState | null;
  dirty: boolean;
  saving: boolean;
  /** An error of the last save that is not about one field. */
  error: string | null;
  /** Errors of the last save about one field. */
  fieldErrors: Partial<Record<EditableField, string>>;
  value: (field: EditableField) => FieldValue;
  change: (field: EditableField, value: FieldValue) => void;
  cancel: () => void;
  save: () => Promise<void>;
};

/**
 * The edit of the comment and the assignee. The draft lives here, apart from the card data, so the
 * 5-second refresh never overwrites it; `PATCH` sends only the changed fields and the version the edit
 * started from.
 */
export function useCardEditor(card: RequestCard): CardEditor {
  const queryClient = useQueryClient();
  const update = useUpdateRequest(card.id);
  const [edit, setEdit] = useState<EditState | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Partial<Record<EditableField, string>>>({});

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
    setError(null);
    setFieldErrors({});
  };

  const save = async () => {
    if (edit === null) return;
    setError(null);
    setFieldErrors({});
    try {
      await update.mutateAsync(patchBody(edit));
      setEdit(null);
      notifications.show({ message: 'Сохранено', color: 'green', autoClose: 2000 });
    } catch (failure) {
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
    }
  };

  return {
    edit,
    dirty: edit !== null,
    saving: update.isPending,
    error,
    fieldErrors,
    value: (field) => displayValue(edit, card, field),
    change,
    cancel,
    save,
  };
}
