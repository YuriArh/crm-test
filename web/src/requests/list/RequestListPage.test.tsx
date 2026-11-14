// @vitest-environment jsdom
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { delay, http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import type { RequestPage } from '@/shared/api';
import { demoInitiator, problem, server } from '@/test/server';
import { renderApp } from '@/test/renderApp';
import { demoOperator, findRow, listItem, listPage, systemInfoHandler } from './fixtures';

/** Serves `answer` for the list and records the query string of every call. */
function serveList(answer: RequestPage | ((query: URLSearchParams) => RequestPage | Response)) {
  const calls: URLSearchParams[] = [];
  server.use(
    http.get('*/api/requests', ({ request }) => {
      const query = new URL(request.url).searchParams;
      calls.push(query);
      const result = typeof answer === 'function' ? answer(query) : answer;
      return result instanceof Response ? result : HttpResponse.json(result);
    }),
    systemInfoHandler,
  );
  return calls;
}

const table = () => screen.getByRole('table');

describe('RequestListPage', () => {
  it('shows the rows: number, cargo, deadline, statuses, assignee and creation time', async () => {
    serveList(
      listPage([
        listItem({
          number: 'DR-000128',
          cargoDescription: 'Паллеты с плиткой',
          deadline: '2026-12-01',
          businessStatus: 'IN_PROGRESS',
          deliveryStatus: 'ACKNOWLEDGED',
          assignee: { id: demoOperator.id, displayName: 'Оператор (Север)' },
          createdAt: '2026-11-12T09:15:00Z',
        }),
        listItem({ number: 'DR-000127', deadline: '2026-11-12', overdue: true, businessStatus: 'AWAITING' }),
      ]),
    );
    renderApp('/requests', { me: demoInitiator });

    const first = await findRow('DR-000128');
    expect(within(first).getByText('Паллеты с плиткой')).toBeInTheDocument();
    expect(within(first).getByText('01.12.2026')).toBeInTheDocument();
    expect(within(first).getByText('В работе')).toBeInTheDocument();
    expect(within(first).getByText('Получена перевозчиком')).toBeInTheDocument();
    expect(within(first).getByText('Оператор (Север)')).toBeInTheDocument();
    expect(within(first).getByText('12.11, 12:15')).toBeInTheDocument();

    const overdue = await findRow('DR-000127');
    expect(within(overdue).getByText('12.11.2026')).toBeInTheDocument();
    expect(within(overdue).getByText(/просрочена/)).toBeInTheDocument();
    expect(within(overdue).getByText('—')).toBeInTheDocument();

    expect(screen.getByTestId('request-total')).toHaveTextContent('2');
    expect(screen.getByText('1–2 из 2')).toBeInTheDocument();
  });

  it('has phone cards: number, result, cargo, deadline, and the delivery only when it is not the usual one', async () => {
    const retrying = listItem({ number: 'DR-000150', deliveryStatus: 'RETRY_WAIT', overdue: true, deadline: '2026-11-12' });
    const usual = listItem({ number: 'DR-000151', cargoDescription: 'Документы', deliveryStatus: 'ACKNOWLEDGED' });
    serveList(listPage([retrying, usual]));
    renderApp('/requests', { me: demoOperator });
    await findRow('DR-000150');

    // jsdom is as wide as a desktop, so the cards are hidden by CSS: query them with `hidden: true`.
    const cards = within(screen.getByTestId('request-cards'));
    const first = cards.getByRole('link', { name: /DR-000150/, hidden: true });
    expect(first).toHaveAttribute('href', `/requests/${retrying.id}`);
    expect(first).toHaveTextContent('до 12.11');
    expect(first).toHaveTextContent('просрочена');
    expect(first).toHaveTextContent('Повтор отправки');
    expect(first).toHaveTextContent('Инициатор (Север)');
    const second = cards.getByRole('link', { name: /DR-000151/, hidden: true });
    expect(second).toHaveTextContent('Документы');
    expect(second).not.toHaveTextContent('Получена перевозчиком');
  });

  it('asks for the first page of 20, newest first', async () => {
    const calls = serveList(listPage([listItem()]));
    renderApp('/requests', { me: demoInitiator });

    await screen.findByRole('table');
    expect(Object.fromEntries(calls[0]!)).toEqual({ page: '0', size: '20', sort: 'createdAt,desc' });
  });

  it('shows an initiator no author column and the simplified delivery status', async () => {
    serveList(listPage([listItem({ number: 'DR-000201', deliveryStatus: 'RETRY_WAIT' })]));
    renderApp('/requests', { me: demoInitiator });

    const row = await findRow('DR-000201');
    expect(within(row).getByText('Отправляется')).toBeInTheDocument();
    expect(within(table()).queryByRole('columnheader', { name: 'Автор' })).not.toBeInTheDocument();
    expect(within(table()).getByRole('columnheader', { name: 'Создана' })).toBeInTheDocument();
  });

  it('shows an operator the author and every delivery state', async () => {
    serveList(
      listPage([
        listItem({
          number: 'DR-000301',
          deliveryStatus: 'RETRY_WAIT',
          owner: { id: 'u-south', displayName: 'Инициатор (Юг)' },
        }),
      ]),
    );
    renderApp('/requests', { me: demoOperator });

    const row = await findRow('DR-000301');
    expect(within(row).getByText('Повтор отправки')).toBeInTheDocument();
    expect(within(row).getByText('Инициатор (Юг)')).toBeInTheDocument();
    expect(within(table()).getByRole('columnheader', { name: 'Автор' })).toBeInTheDocument();
  });

  it('shows a skeleton while the first page loads', async () => {
    server.use(
      http.get('*/api/requests', async () => {
        await delay('infinite');
        return HttpResponse.json(listPage([]));
      }),
      systemInfoHandler,
    );
    renderApp('/requests', { me: demoInitiator });

    expect(await screen.findAllByTestId('skeleton-row')).toHaveLength(8);
  });

  it('says that an initiator has no requests yet', async () => {
    serveList(listPage([]));
    renderApp('/requests', { me: demoInitiator });

    expect(await screen.findByText('Заявок пока нет')).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('says that the service of an operator has no requests yet', async () => {
    serveList(listPage([]));
    renderApp('/requests', { me: demoOperator });

    expect(await screen.findByText('В службе пока нет заявок')).toBeInTheDocument();
  });

  it('reports a failure in place of the table, and "Повторить" loads again', async () => {
    let fail = true;
    serveList(() => (fail ? problem(500, 'INTERNAL_ERROR') : listPage([listItem({ number: 'DR-000401' })])));
    renderApp('/requests', { me: demoInitiator });

    expect(await screen.findByRole('alert', {}, { timeout: 5000 })).toHaveTextContent('Не удалось загрузить заявки');
    fail = false;
    await userEvent.click(screen.getByRole('button', { name: 'Повторить' }));

    expect(await findRow('DR-000401')).toBeInTheDocument();
  });

  it('goes to page 2: the page is in the address and in the query', async () => {
    const calls = serveList((query) => {
      const page = Number(query.get('page'));
      return listPage([listItem({ number: page === 0 ? 'DR-000501' : 'DR-000502' })], { page, totalElements: 30 });
    });
    const { history } = renderApp('/requests', { me: demoInitiator });
    await findRow('DR-000501');

    await userEvent.click(screen.getByRole('button', { name: 'Страница 2' }));

    expect(await findRow('DR-000502')).toBeInTheDocument();
    expect(history.location.search).toBe('?page=2');
    expect(calls.at(-1)?.get('page')).toBe('1');
    expect(screen.getByText('21–30 из 30')).toBeInTheDocument();
  });

  it('replaces a page past the end with the last page', async () => {
    serveList((query) => {
      const page = Number(query.get('page'));
      return listPage(page === 1 ? [listItem({ number: 'DR-000602' })] : [], { page, totalElements: 25 });
    });
    const { history } = renderApp('/requests?page=7', { me: demoInitiator });

    expect(await findRow('DR-000602')).toBeInTheDocument();
    expect(history.location.search).toBe('?page=2');
  });

  it('opens the card when a row is clicked', async () => {
    const item = listItem({ number: 'DR-000701' });
    serveList(listPage([item]));
    const { history } = renderApp('/requests', { me: demoInitiator });

    const link = await screen.findByRole('link', { name: 'DR-000701' });
    expect(link).toHaveAttribute('href', `/requests/${item.id}`);
    await userEvent.click(link);

    await waitFor(() => expect(history.location.pathname).toBe(`/requests/${item.id}`));
  });

  it('drops a broken address that the API rejects', async () => {
    serveList((query) => (query.get('businessStatus') ? problem(400, 'VALIDATION_FAILED') : listPage([listItem({ number: 'DR-000801' })])));
    const { history } = renderApp('/requests?result=REJECTED', { me: demoInitiator });

    expect(await findRow('DR-000801')).toBeInTheDocument();
    expect(history.location.search).toBe('');
  });

  it('drops unknown values of the address instead of failing', async () => {
    const calls = serveList(listPage([listItem({ number: 'DR-000901' })]));
    const { history } = renderApp('/requests?result=foo&delivery=FAILED&page=abc&sort=old', { me: demoInitiator });

    await findRow('DR-000901');
    expect(Object.fromEntries(calls[0]!)).toEqual({ page: '0', size: '20', sort: 'createdAt,asc' });
    await waitFor(() => expect(history.location.search).toBe('?sort=old'));
  });

  it('is what / leads to', async () => {
    serveList(listPage([]));
    const { history } = renderApp('/', { me: demoInitiator });

    expect(await screen.findByRole('heading', { name: 'Заявки' })).toBeInTheDocument();
    expect(history.location.pathname).toBe('/requests');
  });
});
