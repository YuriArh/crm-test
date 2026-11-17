import { describe, expect, it } from 'vitest';
import type { Reconciliation } from '@/shared/api';
import { carrierStateLabel, OUTCOME_VIEWS, triggerLabel } from './reconciliation';

type Outcome = Reconciliation['outcome'];
type Trigger = Reconciliation['trigger'];

// Every value of the contract: a new one breaks the build here until it gets its words.
const OUTCOMES = Object.keys({
  MATCH: 1,
  EXTERNAL_AHEAD: 1,
  EXTERNAL_MISSING: 1,
  CONFLICT: 1,
  ERROR: 1,
} satisfies Record<Outcome, 1>) as Outcome[];

describe('reconciliation words (web-problems.md §8.3)', () => {
  it.each(OUTCOMES)('names the outcome %s with a word and a colour', (outcome) => {
    expect(OUTCOME_VIEWS[outcome].label.length).toBeGreaterThan(3);
    expect(OUTCOME_VIEWS[outcome].color).toBeTruthy();
  });

  it('uses the words and colours of the spec', () => {
    expect(OUTCOME_VIEWS).toEqual({
      MATCH: { label: 'Совпадает', color: 'green' },
      EXTERNAL_AHEAD: { label: 'Итог получен сверкой', color: 'accent' },
      EXTERNAL_MISSING: { label: 'Перевозчик не знает операцию', color: 'orange' },
      CONFLICT: { label: 'Расхождение', color: 'red' },
      ERROR: { label: 'Не удалось сверить', color: 'yellow' },
    });
  });

  it.each([
    ['STARTUP', null, 'при старте'],
    ['SCHEDULED', null, 'по расписанию'],
    ['MANUAL', { id: 'u1', displayName: 'Иванов' }, 'вручную · Иванов'],
    ['MANUAL', null, 'вручную'],
  ] as [Trigger, Reconciliation['performedBy'], string][])('names the run %s', (trigger, performedBy, words) => {
    expect(triggerLabel(trigger, performedBy)).toBe(words);
  });

  it.each([
    ['ACCEPTED', 'Принята, не исполнена'],
    ['EXECUTED', 'Исполнена'],
    ['NOT_FOUND', 'Операции нет'],
    [null, 'Не ответил'],
  ] as [Reconciliation['carrierState'], string][])('names what the carrier said: %s', (state, words) => {
    expect(carrierStateLabel(state)).toBe(words);
  });
});
