import type { RequestCard, UpdateRequest } from '@/shared/api';

/**
 * The draft of an edit of the card, kept apart from the card data so a refresh never overwrites it
 * (spec §3.3–3.4). Only the comment and the assignee can be edited.
 *
 * Each edited field remembers its `base`, the server value when the user started editing it, and the
 * draft keeps the `version` of the card when the edit began: `PATCH` sends that version, so a change made
 * meanwhile by someone else is noticed by the server (`409`) instead of being overwritten.
 */
export type EditableField = 'comment' | 'assigneeId';
export type FieldValue = string | null;
export type FieldEdit = { base: FieldValue; value: FieldValue };
export type EditState = { version: number; fields: Partial<Record<EditableField, FieldEdit>> };

export const EDITABLE_FIELDS: readonly EditableField[] = ['comment', 'assigneeId'];

export const COMMENT_MAX_LENGTH = 2000;

/** The value of `field` on the server's card. */
export function serverValue(card: RequestCard, field: EditableField): FieldValue {
  return field === 'comment' ? card.comment : (card.assignee?.id ?? null);
}

/** The server stores a comment stripped, and an empty one as no comment. */
function normalize(field: EditableField, value: FieldValue): FieldValue {
  if (field !== 'comment' || value === null) return value;
  const trimmed = value.trim();
  return trimmed === '' ? null : trimmed;
}

/** Whether two values of `field` mean the same on the server. */
export function sameValue(field: EditableField, a: FieldValue, b: FieldValue): boolean {
  return normalize(field, a) === normalize(field, b);
}

/** Puts `value` into the draft. A value equal to the field's base drops the field; an empty draft is `null`. */
export function editField(
  state: EditState | null,
  card: RequestCard,
  field: EditableField,
  value: FieldValue,
): EditState | null {
  const current = state ?? { version: card.version, fields: {} };
  const existing = current.fields[field];
  const base = existing ? existing.base : serverValue(card, field);
  const fields = { ...current.fields };
  if (sameValue(field, value, base)) {
    delete fields[field];
  } else {
    fields[field] = { base, value };
  }
  return Object.keys(fields).length === 0 ? null : { version: current.version, fields };
}

/** What a field shows: the draft while it is edited, the server value otherwise. */
export function displayValue(state: EditState | null, card: RequestCard, field: EditableField): FieldValue {
  const edit = state?.fields[field];
  return edit ? edit.value : serverValue(card, field);
}

/** The body of `PATCH`: the version the edit started from and only the changed fields; empty comment = `null`. */
export function patchBody(state: EditState): UpdateRequest {
  const body: UpdateRequest = { version: state.version };
  const comment = state.fields.comment;
  if (comment) body.comment = normalize('comment', comment.value);
  const assignee = state.fields.assigneeId;
  if (assignee) body.assigneeId = assignee.value;
  return body;
}
