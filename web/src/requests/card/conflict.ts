import type { RequestCard } from '@/shared/api';
import { EDITABLE_FIELDS, sameValue, serverValue, type EditableField, type EditState, type FieldEdit } from './edit';

/**
 * Someone else changed the request while the user was editing it (NF03, spec §3.3). Pure functions over
 * the draft, its base and the current card:
 * - another field changed (status, the other editable field) → send the draft again on the new version;
 * - the same field changed to a different value → a conflict on that field, never overwritten silently;
 * - the same field changed to exactly the user's value → nothing to argue about.
 */

/** Fields of the draft that the card now has with a value other than both the base and the draft. */
export function conflictingFields(state: EditState | null, card: RequestCard): EditableField[] {
  if (state === null) return [];
  return EDITABLE_FIELDS.filter((field) => {
    const edit = state.fields[field];
    if (!edit) return false;
    const theirs = serverValue(card, field);
    return !sameValue(field, theirs, edit.base) && !sameValue(field, theirs, edit.value);
  });
}

/**
 * The draft moved onto `card`: its version, and each field's base is the card's value. Fields the card
 * already has with the user's value are done and dropped; `null` when nothing is left to send.
 */
export function rebase(state: EditState, card: RequestCard): EditState | null {
  const fields: Partial<Record<EditableField, FieldEdit>> = {};
  for (const field of EDITABLE_FIELDS) {
    const edit = state.fields[field];
    if (!edit) continue;
    const theirs = serverValue(card, field);
    if (!sameValue(field, theirs, edit.value)) fields[field] = { base: theirs, value: edit.value };
  }
  return Object.keys(fields).length === 0 ? null : { version: card.version, fields };
}

export type ConflictOutcome =
  /** Nobody touched the edited fields: send `state` (on the new version). */
  | { kind: 'resend'; state: EditState }
  /** The card already holds every value of the draft. */
  | { kind: 'settled' }
  /** The same fields were changed by someone else: the user decides. */
  | { kind: 'conflict'; fields: EditableField[] };

/** What to do after `409 VERSION_CONFLICT`, given the current card from the answer. */
export function resolveConflict(state: EditState, current: RequestCard): ConflictOutcome {
  const fields = conflictingFields(state, current);
  if (fields.length > 0) return { kind: 'conflict', fields };
  const next = rebase(state, current);
  return next === null ? { kind: 'settled' } : { kind: 'resend', state: next };
}

/** Moves onto the card once no conflict is left; until then only the decided field's base changes. */
function settle(state: EditState, card: RequestCard): EditState | null {
  return conflictingFields(state, card).length === 0 ? rebase(state, card) : state;
}

/** «Оставить моё»: the user's value stays, now based on theirs, ready to be sent on the new version. */
export function keepMine(state: EditState, card: RequestCard, field: EditableField): EditState | null {
  const edit = state.fields[field];
  if (!edit) return state;
  const decided = { ...state, fields: { ...state.fields, [field]: { base: serverValue(card, field), value: edit.value } } };
  return settle(decided, card);
}

/** «Взять их»: the field leaves the draft and shows the card's value. */
export function takeTheirs(state: EditState, card: RequestCard, field: EditableField): EditState | null {
  const fields = { ...state.fields };
  delete fields[field];
  if (Object.keys(fields).length === 0) return null;
  return settle({ ...state, fields }, card);
}
