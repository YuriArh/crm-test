// @vitest-environment jsdom
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { listHandlers } from '@/requests/list/fixtures';
import { demoInitiator, problem, server } from '@/test/server';
import { renderApp } from '@/test/renderApp';
import { CARD_REFRESH_MS } from './api';
import {
  cardPath,
  demoOperator,
  pendingCard,
  rejectedCard,
  serveCard,
  serveOperators,
  succeededCard,
  withExchange,
} from './testData';

const plain = (text: string | null) => (text ?? '').replace(/\s/g, ' ');

describe('request card', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('shows the number, both statuses, the content and that it cannot be changed', async () => {
    serveCard(pendingCard({ overdue: true }));
    renderApp(cardPath, { me: demoInitiator });

    expect(await screen.findByRole('heading', { name: 'DR-000123', level: 1 })).toBeInTheDocument();
    expect(screen.getByText('В работе')).toBeInTheDocument();
    // An initiator sees the in-between delivery states as one "Отправляется".
    expect(screen.getByText('Отправляется')).toBeInTheDocument();
    expect(screen.getByText('просрочена')).toBeInTheDocument();
    const content = screen.getByRole('region', { name: 'Содержимое' });
    expect(within(content).getByText('Паллеты')).toBeInTheDocument();
    expect(plain(within(content).getByText(/кг/).textContent)).toBe('· 12,5 кг');
    expect(within(content).getByText('ул. Ленина, 1')).toBeInTheDocument();
    expect(within(content).getByText('01.12.2026')).toBeInTheDocument();
    expect(within(content).getByText('Содержимое заявки не меняется после создания')).toBeInTheDocument();
    expect(screen.getByText('Результата пока нет')).toBeInTheDocument();
  });

  it('shows the properties with instants in the business zone', async () => {
    serveCard(pendingCard({ assignee: { id: demoOperator.id, displayName: 'Оператор (Север)' } }));
    renderApp(cardPath, { me: demoInitiator });

    const properties = await screen.findByRole('region', { name: 'Свойства' });
    expect(within(properties).getByText('Оператор (Север)')).toBeInTheDocument();
    expect(within(properties).getByText('Инициатор (Север)')).toBeInTheDocument();
    expect(within(properties).getByText('12.11, 12:15')).toBeInTheDocument();
    expect(within(properties).getByText('12.11, 12:16')).toBeInTheDocument();
  });

  it('says "Не назначен" when nobody is assigned', async () => {
    serveCard(pendingCard());
    renderApp(cardPath, { me: demoInitiator });

    const properties = await screen.findByRole('region', { name: 'Свойства' });
    expect(within(properties).getByText('Не назначен')).toBeInTheDocument();
  });

  it('shows a succeeded result: tracking number to copy, price in rubles, time of the result', async () => {
    serveCard(succeededCard());
    renderApp(cardPath, { me: demoInitiator });

    const result = await screen.findByRole('region', { name: 'Результат' });
    expect(screen.getByText('Выполнена')).toBeInTheDocument();
    expect(screen.getByText('Получена перевозчиком')).toBeInTheDocument();
    expect(within(result).getByText('TRK-3F1C2B4D')).toBeInTheDocument();
    expect(within(result).getByRole('button', { name: 'Скопировать номер отправления' })).toBeInTheDocument();
    expect(plain(within(result).getByText(/₽/).textContent)).toBe('612,50 ₽');
    expect(within(result).getByText('12.11, 12:15')).toBeInTheDocument();
  });

  it('shows the reason of a rejection', async () => {
    serveCard(rejectedCard());
    renderApp(cardPath, { me: demoInitiator });

    const result = await screen.findByRole('region', { name: 'Результат' });
    expect(screen.getByText('Отказ')).toBeInTheDocument();
    expect(within(result).getByText('Адрес вне зоны доставки')).toBeInTheDocument();
  });

  it('hides the exchange block from initiators', async () => {
    serveCard(pendingCard());
    renderApp(cardPath, { me: demoInitiator });

    await screen.findByRole('heading', { name: 'DR-000123', level: 1 });
    expect(screen.queryByRole('region', { name: 'Обмен с перевозчиком' })).not.toBeInTheDocument();
  });

  it('shows operators the exchange and every delivery state', async () => {
    serveCard(withExchange(pendingCard()));
    serveOperators();
    renderApp(cardPath, { me: demoOperator });

    const exchange = await screen.findByRole('region', { name: 'Обмен с перевозчиком' });
    expect(screen.getByText('Неизвестно, получена ли')).toBeInTheDocument();
    expect(within(exchange).getByText('2')).toBeInTheDocument();
    expect(within(exchange).getByText('Перевозчик недоступен (503)')).toBeInTheDocument();
    expect(within(exchange).getByText(/ответ на отправку/)).toBeInTheDocument();
    expect(within(exchange).getByText('0195f3a2-7c41-7d10-9a5e-3f1c2b4d5e6f')).toBeInTheDocument();
    expect(within(exchange).getByRole('button', { name: 'Скопировать идентификатор операции' })).toBeInTheDocument();
  });

  it('says "Заявка не найдена" for a 404, whatever the reason', async () => {
    serveCard(pendingCard());
    server.use(http.get('*/api/requests/not-a-uuid', () => problem(404, 'NOT_FOUND')));
    renderApp('/requests/not-a-uuid', { me: demoInitiator });

    expect(await screen.findByRole('heading', { name: 'Заявка не найдена' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'К списку заявок' })).toHaveAttribute('href', '/requests');
  });

  it('links the breadcrumbs back to the list', async () => {
    serveCard(pendingCard());
    renderApp(cardPath, { me: demoInitiator });

    expect(await screen.findByRole('link', { name: 'Заявки' })).toHaveAttribute('href', '/requests');
  });

  it('goes back through the history from the breadcrumbs, keeping the filters of the list', async () => {
    server.use(...listHandlers());
    serveCard(pendingCard());
    const { router, history } = renderApp('/requests?result=REJECTED', { me: demoInitiator });
    await screen.findByRole('heading', { name: 'Заявки' });
    await act(() => router.navigate({ to: '/requests/$id', params: { id: pendingCard().id } }));

    await userEvent.click(await screen.findByRole('link', { name: 'Заявки' }));

    await waitFor(() => expect(history.location.href).toBe('/requests?result=REJECTED'));
  });

  it('asks again every 5 s while the request is not final, and the result appears by itself', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    let card = pendingCard();
    const calls = serveCard(() => card);
    renderApp(cardPath, { me: demoInitiator });
    await screen.findByText('Результата пока нет');
    const before = calls.count;

    card = succeededCard();
    await act(() => vi.advanceTimersByTimeAsync(CARD_REFRESH_MS + 100));

    expect(await screen.findByText('TRK-3F1C2B4D')).toBeInTheDocument();
    expect(calls.count).toBe(before + 1);
  });

  it('stops asking once the request is final', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const calls = serveCard(succeededCard());
    renderApp(cardPath, { me: demoInitiator });
    await screen.findByText('TRK-3F1C2B4D');
    const before = calls.count;

    await act(() => vi.advanceTimersByTimeAsync(CARD_REFRESH_MS * 3));

    expect(calls.count).toBe(before);
  });
});
