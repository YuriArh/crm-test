// @vitest-environment jsdom
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { renderApp } from '@/test/renderApp';
import { cardPath, demoOperator, pendingCard, serveCard, serveOperators, withExchange } from './testData';

const desktopMatchMedia = window.matchMedia;

describe('request card on a phone', () => {
  beforeEach(() => {
    // Every "narrower than sm" query matches, as on a phone.
    window.matchMedia = (query: string) => ({ ...desktopMatchMedia(query), matches: query.startsWith('not all') });
  });

  afterEach(() => {
    window.matchMedia = desktopMatchMedia;
  });

  it('puts everything in one column: content, result, properties, comment, exchange', async () => {
    serveCard(withExchange(pendingCard()));
    serveOperators();
    renderApp(cardPath, { me: demoOperator });

    await screen.findByRole('heading', { name: 'DR-000123', level: 1 });
    const order = screen
      .getAllByRole('region')
      .map((region) => region.getAttribute('aria-label'))
      .filter((label) => label !== null);
    expect(order).toEqual(['Содержимое', 'Результат', 'Свойства', 'Комментарий', 'Обмен с перевозчиком']);
  });

  it('shows the save bar only while there are changes', async () => {
    serveCard(withExchange(pendingCard()));
    serveOperators();
    renderApp(cardPath, { me: demoOperator });

    const field = await screen.findByRole('textbox', { name: 'Комментарий' });
    expect(screen.queryByRole('region', { name: 'Несохранённые изменения' })).not.toBeInTheDocument();

    await userEvent.type(field, 'Текст');
    expect(screen.getByRole('region', { name: 'Несохранённые изменения' })).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Отменить' }));
    expect(screen.queryByRole('region', { name: 'Несохранённые изменения' })).not.toBeInTheDocument();
  });
});
