import { DECISION_LABELS, kindView } from '@/problems';
import type { BusinessStatus, HistoryEntry, ProblemDecision } from '@/shared/api';
import { formatInstant } from '../../format';
import { BUSINESS_VIEWS } from '../../statuses';
import { OUTCOME_VIEWS } from '../reconciliation';

type Of<K extends HistoryEntry['kind']> = Extract<HistoryEntry, { kind: K }>;

/**
 * An entry in words: a title, lines under it, and technical details shown on request. `color` is the theme key of
 * its dot on the timeline.
 */
export type EntryView = { title: string; color: string; lines: string[]; details: string[] };

const ATTEMPT_OUTCOMES: Record<NonNullable<Of<'SEND_ATTEMPT'>['outcome']>, string> = {
  ACKNOWLEDGED: 'принята',
  TEMPORARY_ERROR: 'временная ошибка',
  UNKNOWN: 'ответ не получен',
  PERMANENT_ERROR: 'отказ в приёме',
  PAYLOAD_CONFLICT: 'другое содержимое',
};

const PROCESSING: Record<Of<'RESULT_RECEIVED'>['processingStatus'], string> = {
  RECEIVED: 'ждёт обработки',
  APPLIED: 'применена',
  STALE: 'устарела',
  CONFLICT: 'расходится с применённой',
  INVALID: 'некорректна',
  FAILED: 'не обработана',
};

const SOURCES: Record<Of<'RESULT_RECEIVED'>['source'], string> = {
  FEED: 'из ленты',
  RECONCILIATION: 'из сверки',
};

const VIA: Record<NonNullable<Of<'DELIVERY_ACKNOWLEDGED'>['via']>, string> = {
  RESPONSE: 'по ответу на отправку',
  RESULT: 'по пришедшему результату',
  RECONCILIATION: 'по сверке',
};

const TRIGGERS: Record<Of<'RECONCILIATION'>['trigger'], string> = {
  STARTUP: 'при старте',
  SCHEDULED: 'по расписанию',
  MANUAL: 'вручную',
};

export function entryView(entry: HistoryEntry, timeZone: string): EntryView {
  const view = (title: string, color: string, lines: string[] = [], details: string[] = []): EntryView => ({
    title,
    color,
    lines,
    details,
  });
  switch (entry.kind) {
    case 'CREATED':
      return view('Заявка создана', 'accent');
    case 'EDITED':
      return view('Заявка изменена', 'gray');
    case 'RESULT_CHANGED': {
      const status = entry.changes.find((change) => change.field === 'businessStatus');
      const to = status && typeof status.to === 'string' ? BUSINESS_VIEWS[status.to as BusinessStatus] : undefined;
      return to ? view(`Итог: ${to.label}`, to.color) : view('Итог изменён', 'accent');
    }
    case 'DELIVERY_ACKNOWLEDGED':
      return view('Перевозчик получил заявку', 'green', entry.via ? [VIA[entry.via]] : []);
    case 'DELIVERY_RETRIED':
      return view('Отправка повторена', 'accent');
    case 'SEND_ATTEMPT': {
      const outcome = entry.outcome ? ATTEMPT_OUTCOMES[entry.outcome] : 'идёт';
      const color = entry.outcome === 'ACKNOWLEDGED' ? 'green' : entry.outcome === null ? 'gray' : 'orange';
      return view(`Попытка отправки ${entry.attemptNo}: ${outcome}`, color, [], [
        ...(entry.httpStatus !== null ? [`Код ответа: ${entry.httpStatus}`] : []),
        ...(entry.error ? [`Ошибка: ${entry.error}`] : []),
        ...(entry.durationMs !== null ? [`Длительность: ${entry.durationMs} мс`] : []),
      ]);
    }
    case 'RESULT_RECEIVED': {
      const status = entry.resultStatus
        ? (BUSINESS_VIEWS[entry.resultStatus as BusinessStatus]?.label ?? entry.resultStatus)
        : 'не прочитан';
      const color = entry.processingStatus === 'APPLIED' || entry.processingStatus === 'STALE' ? 'gray' : 'orange';
      return view(
        `Запись перевозчика ${SOURCES[entry.source]}: ${PROCESSING[entry.processingStatus]}`,
        color,
        [
          entry.sequence !== null ? `${status} · последовательность ${entry.sequence}` : status,
          ...(entry.duplicates > 0 ? [`Повторов: ${entry.duplicates}`] : []),
        ],
        [
          ...(entry.reason ? [`Причина: ${entry.reason}`] : []),
          `Получена: ${formatInstant(entry.receivedAt, timeZone)}`,
        ],
      );
    }
    case 'RECONCILIATION': {
      const outcome = OUTCOME_VIEWS[entry.outcome];
      return view(`Сверка: ${outcome.label}`, outcome.color, [TRIGGERS[entry.trigger]], [entry.details]);
    }
    case 'PROBLEM_OPENED': {
      const kind = kindView(entry.problemKind);
      return view(`Проблема: ${kind.label}`, kind.color);
    }
    case 'PROBLEM_RESOLVED':
      return view(`Проблема закрыта: ${kindView(entry.problemKind).label}`, 'gray', [
        DECISION_LABELS[entry.decision as ProblemDecision] ?? entry.decision,
        ...(entry.comment ? [`«${entry.comment}»`] : []),
      ]);
    default: {
      // A kind newer than this client is shown by its code, so the screen never breaks.
      const unknown = entry as { kind: string };
      return view(unknown.kind, 'gray');
    }
  }
}

/** The changed fields of an entry, if it has them. */
export function changesOf(entry: HistoryEntry) {
  return 'changes' in entry ? entry.changes : [];
}

/** «Действия людей»: whatever a person did. */
export function isPeople(entry: HistoryEntry): boolean {
  return entry.actor !== null;
}

const EXCHANGE = new Set<string>([
  'RESULT_CHANGED',
  'DELIVERY_ACKNOWLEDGED',
  'DELIVERY_RETRIED',
  'SEND_ATTEMPT',
  'RESULT_RECEIVED',
  'RECONCILIATION',
  'PROBLEM_OPENED',
  'PROBLEM_RESOLVED',
]);

/** «Обмен»: everything between us and the carrier, and what came of it. */
export function isExchange(entry: HistoryEntry): boolean {
  return EXCHANGE.has(entry.kind);
}
