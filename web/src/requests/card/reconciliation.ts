import type { Reconciliation } from '@/shared/api';
import type { StatusView } from '../statuses';

/** The outcome of a reconciliation check as a word and a colour (web-problems.md §8.3). */
export const OUTCOME_VIEWS: Record<Reconciliation['outcome'], StatusView> = {
  MATCH: { label: 'Совпадает', color: 'green' },
  EXTERNAL_AHEAD: { label: 'Итог получен сверкой', color: 'accent' },
  EXTERNAL_MISSING: { label: 'Перевозчик не знает операцию', color: 'orange' },
  CONFLICT: { label: 'Расхождение', color: 'red' },
  ERROR: { label: 'Не удалось сверить', color: 'yellow' },
};

const TRIGGERS: Record<Reconciliation['trigger'], string> = {
  STARTUP: 'при старте',
  SCHEDULED: 'по расписанию',
  MANUAL: 'вручную',
};

/** Who ran a check: a background pass, or «вручную · <operator>». */
export function triggerLabel(trigger: Reconciliation['trigger'], performedBy: Reconciliation['performedBy']): string {
  return performedBy ? `${TRIGGERS[trigger]} · ${performedBy.displayName}` : TRIGGERS[trigger];
}

const CARRIER_STATES: Record<NonNullable<Reconciliation['carrierState']>, string> = {
  ACCEPTED: 'Принята, не исполнена',
  EXECUTED: 'Исполнена',
  NOT_FOUND: 'Операции нет',
};

/** What the carrier said about the operation; `null` — it did not answer. */
export function carrierStateLabel(state: Reconciliation['carrierState']): string {
  return state === null ? 'Не ответил' : CARRIER_STATES[state];
}
