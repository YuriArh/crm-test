import type { ProblemDecision, ProblemKind, ProblemStatus } from '@/shared/api';

/** How a kind of problem is shown: a name, a hint under it, the colour of its notice (a theme key). */
export type KindView = { label: string; hint: string; color: string };

export const KIND_VIEWS: Record<ProblemKind, KindView> = {
  DELIVERY_UNKNOWN: {
    label: 'Неизвестно, получена ли заявка',
    hint: 'Ответ перевозчика потерян. Повтор идёт сам; закроется, когда перевозчик подтвердит получение',
    color: 'orange',
  },
  DELIVERY_FAILED: {
    label: 'Отправка не удалась',
    hint: 'Попытки кончились или перевозчик отказал в приёме',
    color: 'orange',
  },
  INVALID_MESSAGE: {
    label: 'Некорректное сообщение перевозчика',
    hint: 'Запись ленты нарушает контракт и не применена',
    color: 'yellow',
  },
  MESSAGE_FAILED: {
    label: 'Сообщение не обработано',
    hint: 'У нас не получилось обработать запись ленты',
    color: 'yellow',
  },
  RESULT_CONFLICT: {
    label: 'Расхождение итогов',
    hint: 'Перевозчик прислал итог, отличный от применённого',
    color: 'red',
  },
  EXTERNAL_MISSING: {
    label: 'Перевозчик не знает операцию',
    hint: 'Он подтверждал приём, а теперь операции у него нет',
    color: 'orange',
  },
  RECONCILIATION_CONFLICT: {
    label: 'Расхождение при сверке',
    hint: 'Итог перевозчика отличается от нашего или итог есть только у нас',
    color: 'red',
  },
};

/** A kind newer than this client is shown by its code, so the screen never breaks. */
export function kindView(kind: string): KindView {
  return (KIND_VIEWS as Record<string, KindView>)[kind] ?? { label: kind, hint: '', color: 'gray' };
}

/** How a closed problem was closed. */
export const DECISION_LABELS: Record<ProblemDecision, string> = {
  SYSTEM: 'Закрыта системой',
  RETRIED: 'Отправка повторена',
  APPLY_CARRIER: 'Принята версия перевозчика',
  KEEP_OURS: 'Оставлен наш итог',
  ACKNOWLEDGED: 'Разобрано',
};

/** The decisions an operator takes through `resolve`, as button captions. */
export const DECISION_ACTIONS: Partial<Record<ProblemDecision, string>> = {
  APPLY_CARRIER: 'Принять версию перевозчика',
  KEEP_OURS: 'Оставить наш итог',
  ACKNOWLEDGED: 'Разобрано',
};

export const STATUS_VIEWS: Record<ProblemStatus, { label: string; color: string }> = {
  OPEN: { label: 'Открыта', color: 'orange' },
  RESOLVED: { label: 'Закрыта', color: 'gray' },
};
