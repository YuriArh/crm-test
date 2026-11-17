import { describe, expect, it } from 'vitest';
import type { ProblemDecision, ProblemKind } from '@/shared/api';
import { DECISION_LABELS, kindView, KIND_VIEWS, STATUS_VIEWS } from './labels';

// Every value of the contract: a new kind or decision breaks the build here until it gets its words.
const KINDS = Object.keys({
  DELIVERY_UNKNOWN: 1,
  DELIVERY_FAILED: 1,
  INVALID_MESSAGE: 1,
  MESSAGE_FAILED: 1,
  RESULT_CONFLICT: 1,
} satisfies Record<ProblemKind, 1>) as ProblemKind[];

const DECISIONS = Object.keys({
  SYSTEM: 1,
  RETRIED: 1,
  APPLY_CARRIER: 1,
  KEEP_OURS: 1,
  ACKNOWLEDGED: 1,
} satisfies Record<ProblemDecision, 1>) as ProblemDecision[];

describe('problem labels', () => {
  it.each(KINDS)('names %s with a word, a hint and a colour', (kind) => {
    const view = KIND_VIEWS[kind];
    expect(view.label).toMatch(/^[А-Я]/);
    expect(view.hint.length).toBeGreaterThan(10);
    expect(['orange', 'yellow', 'red']).toContain(view.color);
  });

  it('colours delivery problems orange, records yellow and conflicts red', () => {
    expect(KIND_VIEWS.DELIVERY_UNKNOWN.color).toBe('orange');
    expect(KIND_VIEWS.DELIVERY_FAILED.color).toBe('orange');
    expect(KIND_VIEWS.INVALID_MESSAGE.color).toBe('yellow');
    expect(KIND_VIEWS.MESSAGE_FAILED.color).toBe('yellow');
    expect(KIND_VIEWS.RESULT_CONFLICT.color).toBe('red');
  });

  it('shows a kind this client does not know by its code, in grey', () => {
    expect(kindView('SOMETHING_NEW')).toEqual({ label: 'SOMETHING_NEW', hint: '', color: 'gray' });
    expect(kindView('RESULT_CONFLICT')).toBe(KIND_VIEWS.RESULT_CONFLICT);
  });

  it.each(DECISIONS)('names the decision %s', (decision) => {
    expect(DECISION_LABELS[decision]).toMatch(/^[А-Я]/);
  });

  it('names the statuses with a word and a colour', () => {
    expect(STATUS_VIEWS.OPEN).toEqual({ label: 'Открыта', color: 'orange' });
    expect(STATUS_VIEWS.RESOLVED).toEqual({ label: 'Закрыта', color: 'gray' });
  });
});
