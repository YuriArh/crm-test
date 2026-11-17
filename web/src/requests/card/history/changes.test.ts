import { describe, expect, it } from 'vitest';
import { changeView, LONG_TEXT } from './changes';

describe('a changed field in words', () => {
  it('names the field and shows a missing value as a dash', () => {
    expect(changeView({ field: 'comment', from: null, to: 'Позвонить заранее' })).toEqual({
      label: 'Комментарий',
      from: '—',
      to: 'Позвонить заранее',
      long: false,
    });
  });

  it('shows the price in rubles with a comma', () => {
    const view = changeView({ field: 'price', from: '612.5', to: '700' });
    expect(view.label).toBe('Стоимость');
    expect(view.from.replace(/\s/g, ' ')).toBe('612,50 ₽');
    expect(view.to.replace(/\s/g, ' ')).toBe('700,00 ₽');
  });

  it('shows statuses as the card says them', () => {
    expect(changeView({ field: 'businessStatus', from: 'IN_PROGRESS', to: 'SUCCEEDED' })).toMatchObject({
      label: 'Статус',
      from: 'В работе',
      to: 'Выполнена',
    });
    expect(changeView({ field: 'deliveryStatus', from: 'UNKNOWN', to: 'UNKNOWN' })).toMatchObject({
      label: 'Доставка',
      from: 'Неизвестно, получена ли',
      to: 'Неизвестно, получена ли',
    });
  });

  it('shows the assignee by name, nobody as «Не назначен»', () => {
    expect(
      changeView({ field: 'assignee', from: null, to: { id: 'x', displayName: 'Оператор (Север)' } }),
    ).toMatchObject({ label: 'Ответственный', from: 'Не назначен', to: 'Оператор (Север)' });
  });

  it('marks a long text to fold it', () => {
    const text = 'а'.repeat(LONG_TEXT + 1);
    expect(changeView({ field: 'comment', from: text, to: null }).long).toBe(true);
    expect(changeView({ field: 'trackingNumber', from: null, to: 'TRK-1' }).long).toBe(false);
  });

  it('shows a field newer than this client by its code', () => {
    const unknown = { field: 'colour', from: 'a', to: 'b' } as unknown as Parameters<typeof changeView>[0];
    expect(changeView(unknown)).toMatchObject({ label: 'colour', from: 'a', to: 'b' });
  });
});
