import { describe, expect, it } from 'vitest';
import { conflictingFields, keepMine, resolveConflict, takeTheirs } from './conflict';
import { editField, patchBody, type EditState } from './edit';
import { demoOperator, operators, pendingCard } from './testData';

const other = operators[1]!;
const start = pendingCard({ version: 3, comment: 'Исходный' });

/** The user edits the comment of `start`. */
const commentDraft = editField(null, start, 'comment', 'Мой текст')!;

describe('resolveConflict', () => {
  it('resends on the new version when another field changed', () => {
    const current = pendingCard({ version: 4, comment: 'Исходный', assignee: { id: other.id, displayName: other.displayName } });

    const outcome = resolveConflict(commentDraft, current);

    expect(outcome.kind).toBe('resend');
    const state = (outcome as { state: EditState }).state;
    expect(patchBody(state)).toEqual({ version: 4, comment: 'Мой текст' });
  });

  it('reports a conflict when the same field was changed', () => {
    const current = pendingCard({ version: 4, comment: 'Чужой текст' });

    expect(resolveConflict(commentDraft, current)).toEqual({ kind: 'conflict', fields: ['comment'] });
  });

  it('reports only the fields both sides changed when the user edited both', () => {
    const both = editField(commentDraft, start, 'assigneeId', demoOperator.id)!;
    const current = pendingCard({ version: 5, comment: 'Чужой текст', assignee: null });

    expect(resolveConflict(both, current)).toEqual({ kind: 'conflict', fields: ['comment'] });

    const bothChanged = pendingCard({ version: 5, comment: 'Чужой', assignee: { id: other.id, displayName: other.displayName } });
    expect(resolveConflict(both, bothChanged)).toEqual({ kind: 'conflict', fields: ['comment', 'assigneeId'] });
  });

  it('settles when the other user wrote exactly the same value', () => {
    const current = pendingCard({ version: 4, comment: '  Мой текст ' });

    expect(resolveConflict(commentDraft, current)).toEqual({ kind: 'settled' });
  });

  it('drops a field already equal on the server and resends the rest', () => {
    const both = editField(commentDraft, start, 'assigneeId', demoOperator.id)!;
    const current = pendingCard({ version: 4, comment: 'Мой текст', assignee: null });

    const outcome = resolveConflict(both, current);

    expect(outcome.kind).toBe('resend');
    expect(patchBody((outcome as { state: EditState }).state)).toEqual({ version: 4, assigneeId: demoOperator.id });
  });
});

describe('deciding a conflict', () => {
  const current = pendingCard({ version: 6, comment: 'Чужой текст' });

  it('«Оставить моё» keeps the user value and moves onto the new version', () => {
    const state = keepMine(commentDraft, current, 'comment')!;

    expect(conflictingFields(state, current)).toEqual([]);
    expect(patchBody(state)).toEqual({ version: 6, comment: 'Мой текст' });
  });

  it('«Взять их» drops the field; an empty draft is no draft', () => {
    expect(takeTheirs(commentDraft, current, 'comment')).toBeNull();
  });

  it('keeps the old version while another field is still in conflict', () => {
    const both = editField(commentDraft, start, 'assigneeId', demoOperator.id)!;
    const bothChanged = pendingCard({ version: 6, comment: 'Чужой', assignee: { id: other.id, displayName: other.displayName } });

    const decided = keepMine(both, bothChanged, 'comment')!;

    expect(conflictingFields(decided, bothChanged)).toEqual(['assigneeId']);
    expect(decided.version).toBe(3);
    const done = takeTheirs(decided, bothChanged, 'assigneeId')!;
    expect(patchBody(done)).toEqual({ version: 6, comment: 'Мой текст' });
  });
});

describe('conflictingFields', () => {
  it('sees nothing while the card has not touched the edited field', () => {
    expect(conflictingFields(commentDraft, pendingCard({ version: 9, comment: 'Исходный', businessStatus: 'SUCCEEDED' }))).toEqual([]);
    expect(conflictingFields(null, start)).toEqual([]);
  });
});
