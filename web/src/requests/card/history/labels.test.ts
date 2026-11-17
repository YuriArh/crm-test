import { describe, expect, it } from 'vitest';
import type { HistoryEntry, HistoryKind } from '@/shared/api';
import {
  acknowledged,
  attempt,
  checked,
  created,
  edited,
  problemOpened,
  problemResolved,
  received,
  resultChanged,
  retried,
} from './fixtures';
import { entryView, isExchange, isPeople } from './labels';

const ZONE = 'Europe/Moscow';

// Every kind of the contract has its fixture here: a new kind breaks the build until it gets its words.
const EVERY_KIND = {
  CREATED: created(),
  EDITED: edited(),
  RESULT_CHANGED: resultChanged(),
  DELIVERY_ACKNOWLEDGED: acknowledged(),
  DELIVERY_RETRIED: retried(),
  SEND_ATTEMPT: attempt(),
  RESULT_RECEIVED: received(),
  RECONCILIATION: checked(),
  PROBLEM_OPENED: problemOpened(),
  PROBLEM_RESOLVED: problemResolved(),
} satisfies Record<HistoryKind, HistoryEntry>;

describe('an entry in words', () => {
  it('gives every kind a title', () => {
    for (const entry of Object.values(EVERY_KIND)) {
      expect(entryView(entry, ZONE).title).not.toBe(entry.kind);
    }
  });

  it('says what happened to the request', () => {
    expect(entryView(created(), ZONE).title).toBe('Заявка создана');
    expect(entryView(edited(), ZONE).title).toBe('Заявка изменена');
    expect(entryView(resultChanged(), ZONE).title).toBe('Итог: Выполнена');
    expect(entryView(resultChanged({ changes: [{ field: 'price', from: '612.5', to: '700' }] }), ZONE).title).toBe(
      'Итог изменён',
    );
    expect(entryView(acknowledged(), ZONE)).toMatchObject({
      title: 'Перевозчик получил заявку',
      lines: ['по ответу на отправку'],
    });
    expect(entryView(acknowledged({ via: null }), ZONE).lines).toEqual([]);
    expect(entryView(retried(), ZONE).title).toBe('Отправка повторена');
  });

  it('says how the exchange went', () => {
    expect(entryView(attempt(), ZONE)).toMatchObject({
      title: 'Попытка отправки 1: временная ошибка',
      details: ['Код ответа: 503', 'Ошибка: HTTP 503', 'Длительность: 85 мс'],
    });
    expect(entryView(attempt({ outcome: null, httpStatus: null, error: null, durationMs: null }), ZONE)).toMatchObject({
      title: 'Попытка отправки 1: идёт',
      details: [],
    });
    expect(entryView(received({ duplicates: 2 }), ZONE)).toMatchObject({
      title: 'Запись перевозчика из ленты: применена',
      lines: ['Выполнена · последовательность 2', 'Повторов: 2'],
    });
    expect(entryView(received({ source: 'RECONCILIATION', processingStatus: 'CONFLICT', reason: 'Differs' }), ZONE))
      .toMatchObject({
        title: 'Запись перевозчика из сверки: расходится с применённой',
        details: ['Причина: Differs', 'Получена: 12.11, 12:15'],
      });
    expect(entryView(checked(), ZONE)).toMatchObject({ title: 'Сверка: Расхождение', lines: ['по расписанию'], details: ['Results differ'] });
    expect(entryView(problemOpened(), ZONE).title).toBe('Проблема: Расхождение при сверке');
    expect(entryView(problemResolved(), ZONE)).toMatchObject({
      title: 'Проблема закрыта: Расхождение при сверке',
      lines: ['Оставлен наш итог', '«Наш итог верный»'],
    });
  });

  it('shows a kind newer than this client by its code', () => {
    const unknown = { ...created(), kind: 'ARCHIVED' } as unknown as HistoryEntry;
    expect(entryView(unknown, ZONE).title).toBe('ARCHIVED');
  });
});

describe('the filters of an operator', () => {
  it('«Действия людей» are the entries with an author', () => {
    expect(isPeople(edited())).toBe(true);
    expect(isPeople(retried())).toBe(true);
    expect(isPeople(resultChanged({ actor: { id: 'x', displayName: 'Оператор' } }))).toBe(true);
    expect(isPeople(resultChanged())).toBe(false);
    expect(isPeople(attempt())).toBe(false);
  });

  it('«Обмен» is everything between us and the carrier', () => {
    const exchange = Object.values(EVERY_KIND).filter(isExchange).map((entry) => entry.kind);
    expect(exchange).toEqual([
      'RESULT_CHANGED',
      'DELIVERY_ACKNOWLEDGED',
      'DELIVERY_RETRIED',
      'SEND_ATTEMPT',
      'RESULT_RECEIVED',
      'RECONCILIATION',
      'PROBLEM_OPENED',
      'PROBLEM_RESOLVED',
    ]);
  });
});
