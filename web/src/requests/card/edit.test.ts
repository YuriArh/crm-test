import { describe, expect, it } from 'vitest';
import { displayValue, editField, patchBody, sameValue } from './edit';
import { demoOperator, pendingCard } from './testData';

const card = pendingCard({ version: 4, comment: 'Позвонить за час' });

describe('editField', () => {
  it('starts an edit from the card: its version and the field value as the base', () => {
    const state = editField(null, card, 'comment', 'Позвонить за два часа');

    expect(state).toEqual({
      version: 4,
      fields: { comment: { base: 'Позвонить за час', value: 'Позвонить за два часа' } },
    });
  });

  it('keeps the version and base of the start even when the card has changed since', () => {
    const started = editField(null, card, 'comment', 'А');
    const later = pendingCard({ version: 6, comment: 'Чужой текст' });

    expect(editField(started, later, 'comment', 'Б')).toEqual({
      version: 4,
      fields: { comment: { base: 'Позвонить за час', value: 'Б' } },
    });
  });

  it('drops a field typed back to its base, and the whole draft when nothing is left', () => {
    const started = editField(null, card, 'comment', 'Другое');

    expect(editField(started, card, 'comment', '  Позвонить за час ')).toBeNull();
  });

  it('shows the draft while a field is edited and the server value otherwise', () => {
    const state = editField(null, card, 'assigneeId', demoOperator.id);

    expect(displayValue(state, card, 'assigneeId')).toBe(demoOperator.id);
    expect(displayValue(state, card, 'comment')).toBe('Позвонить за час');
  });
});

describe('sameValue', () => {
  it('treats a comment as the server stores it: stripped, empty = none', () => {
    expect(sameValue('comment', '', null)).toBe(true);
    expect(sameValue('comment', '  a ', 'a')).toBe(true);
    expect(sameValue('assigneeId', null, 'x')).toBe(false);
  });
});

describe('patchBody', () => {
  it('sends the start version and only the changed field', () => {
    const state = editField(null, card, 'comment', 'Новый')!;

    expect(patchBody(state)).toEqual({ version: 4, comment: 'Новый' });
  });

  it('clears with an explicit null', () => {
    const noComment = editField(null, card, 'comment', '   ')!;
    expect(patchBody(noComment)).toEqual({ version: 4, comment: null });

    const assigned = pendingCard({ version: 2, assignee: { id: demoOperator.id, displayName: 'Оператор (Север)' } });
    expect(patchBody(editField(null, assigned, 'assigneeId', null)!)).toEqual({ version: 2, assigneeId: null });
  });

  it('sends both fields when both changed', () => {
    const one = editField(null, card, 'comment', 'Новый');
    const both = editField(one, card, 'assigneeId', demoOperator.id)!;

    expect(patchBody(both)).toEqual({ version: 4, comment: 'Новый', assigneeId: demoOperator.id });
  });
});
