// @vitest-environment jsdom
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { demoInitiator, problem, server } from '@/test/server';
import { renderApp } from '@/test/renderApp';
import { CARD_REFRESH_MS } from '../api';
import { CARD_ID, cardPath, demoOperator, pendingCard, serveCard, serveOperators, succeededCard, withExchange } from '../testData';
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
  serveHistory,
} from './fixtures';

const operatorHistory = [
  edited(),
  problemResolved(),
  problemOpened(),
  checked(),
  resultChanged(),
  received(),
  acknowledged(),
  attempt({ attemptNo: 2, outcome: 'ACKNOWLEDGED', httpStatus: 202, error: null }),
  attempt(),
  created(),
];

async function section() {
  return screen.findByRole('region', { name: 'История' });
}

/** The titles of the entries shown, top to bottom. */
function titles(region: HTMLElement): string[] {
  return within(region)
    .getAllByRole('listitem')
    .map((item) => within(item).getByRole('heading').textContent ?? '');
}

function renderOperator(...pages: Parameters<typeof serveHistory>) {
  serveCard(withExchange(succeededCard()));
  serveOperators();
  const seen = serveHistory(...pages);
  renderApp(cardPath, { me: demoOperator });
  return seen;
}

describe('history of the request', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('lists everything for an operator, newest first: what, when in the business zone, who', async () => {
    renderOperator(operatorHistory);

    const history = await section();
    await waitFor(() => expect(within(history).getAllByRole('listitem')).toHaveLength(10));
    expect(titles(history)).toEqual([
      'Заявка изменена',
      'Проблема закрыта: Расхождение при сверке',
      'Проблема: Расхождение при сверке',
      'Сверка: Расхождение',
      'Итог: Выполнена',
      'Запись перевозчика из ленты: применена',
      'Перевозчик получил заявку',
      'Попытка отправки 2: принята',
      'Попытка отправки 1: временная ошибка',
      'Заявка создана',
    ]);
    const [edit, , , , result, , , , , creation] = within(history).getAllByRole('listitem');
    expect(edit).toHaveTextContent('12.11, 12:30');
    expect(edit).toHaveTextContent('Оператор (Север)');
    expect(edit).toHaveTextContent('Комментарий');
    expect(edit).toHaveTextContent('Позвонить заранее');
    expect(result).toHaveTextContent('Система');
    expect(result).toHaveTextContent('TRK-3F1C2B4D');
    expect(creation).toHaveTextContent('Инициатор (Север)');
  });

  it('filters an operator’s history to the people’s actions or to the exchange', async () => {
    const user = userEvent.setup();
    renderOperator(operatorHistory);
    const history = await section();
    await waitFor(() => expect(within(history).getAllByRole('listitem')).toHaveLength(10));

    await user.click(within(history).getByRole('radio', { name: 'Действия людей' }));
    expect(titles(history)).toEqual([
      'Заявка изменена',
      'Проблема закрыта: Расхождение при сверке',
      'Заявка создана',
    ]);

    await user.click(within(history).getByRole('radio', { name: 'Обмен' }));
    expect(titles(history)).not.toContain('Заявка изменена');
    expect(titles(history)).not.toContain('Заявка создана');
    expect(titles(history)).toContain('Попытка отправки 1: временная ошибка');
  });

  it('shows an initiator the business part without a filter', async () => {
    serveCard(succeededCard());
    serveHistory([edited(), resultChanged({ recordId: null }), acknowledged({ via: null }), created()]);
    renderApp(cardPath, { me: demoInitiator });

    const history = await section();
    await waitFor(() => expect(within(history).getAllByRole('listitem')).toHaveLength(4));
    expect(within(history).queryByRole('radio')).not.toBeInTheDocument();
    expect(titles(history)).toContain('Перевозчик получил заявку');
  });

  it('loads older entries below with «Показать ещё»', async () => {
    const user = userEvent.setup();
    const seen = renderOperator([edited(), resultChanged()], [attempt(), created()]);
    const history = await section();
    await waitFor(() => expect(within(history).getAllByRole('listitem')).toHaveLength(2));

    await user.click(within(history).getByRole('button', { name: 'Показать ещё' }));

    await waitFor(() => expect(within(history).getAllByRole('listitem')).toHaveLength(4));
    expect(titles(history).at(-1)).toBe('Заявка создана');
    expect(seen.at(-1)?.searchParams.get('before')).toBe('cursor-1');
    expect(within(history).queryByRole('button', { name: 'Показать ещё' })).not.toBeInTheDocument();
  });

  it('says it could not load the history and offers to try again; the card works', async () => {
    const user = userEvent.setup();
    serveCard(succeededCard());
    server.use(http.get(`*/api/requests/${CARD_ID}/history`, () => problem(404, 'NOT_FOUND')));
    renderApp(cardPath, { me: demoInitiator });

    const history = await section();
    expect(await within(history).findByText('Не удалось загрузить историю')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'DR-000123', level: 1 })).toBeInTheDocument();

    serveHistory([created()]);
    await user.click(within(history).getByRole('button', { name: 'Повторить' }));
    expect(await within(history).findByText('Заявка создана')).toBeInTheDocument();
  });

  it('asks again when the card shows a change', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    let card = pendingCard();
    serveCard(() => card);
    const seen = serveHistory([created()]);
    renderApp(cardPath, { me: demoInitiator });
    await within(await section()).findByText('Заявка создана');
    const before = seen.length;

    card = succeededCard();
    await act(() => vi.advanceTimersByTimeAsync(CARD_REFRESH_MS + 100));

    await waitFor(() => expect(seen.length).toBe(before + 1));
  });

  it('opens the technical details and loads more from the keyboard', async () => {
    const user = userEvent.setup();
    renderOperator([attempt()], [created()]);
    const history = await section();
    await within(history).findByText('Попытка отправки 1: временная ошибка');

    const details = within(history).getByRole('button', { name: 'Подробнее' });
    details.focus();
    await user.keyboard('{Enter}');
    expect(details).toHaveAttribute('aria-expanded', 'true');
    expect(within(history).getByText('Ошибка: HTTP 503')).toBeVisible();

    const more = within(history).getByRole('button', { name: 'Показать ещё' });
    more.focus();
    await user.keyboard('{Enter}');
    expect(await within(history).findByText('Заявка создана')).toBeInTheDocument();
  });
});
