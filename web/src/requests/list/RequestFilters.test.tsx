// @vitest-environment jsdom
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import type { RequestPage } from '@/shared/api';
import { demoInitiator, server } from '@/test/server';
import { renderApp } from '@/test/renderApp';
import { demoOperator, listItem, listPage, systemInfoHandler } from './fixtures';

// Mantine's combobox scrolls the selected option into view; jsdom has no layout.
Element.prototype.scrollIntoView ??= () => {};

function serveList(answer: (query: URLSearchParams) => RequestPage = () => listPage([listItem()], { totalElements: 60 })) {
  const calls: URLSearchParams[] = [];
  server.use(
    http.get('*/api/requests', ({ request }) => {
      const query = new URL(request.url).searchParams;
      calls.push(query);
      return HttpResponse.json(answer(query));
    }),
    systemInfoHandler,
  );
  return calls;
}

async function choose(field: string, option: string) {
  await userEvent.click(await screen.findByRole('combobox', { name: field }));
  // The dropdown is still in its opening transition, which jsdom never finishes: count it as visible.
  await userEvent.click(await screen.findByRole('option', { name: option, hidden: true }));
}

describe('RequestFilterBar', () => {
  it('a result filter goes into the address and the query, back on the first page', async () => {
    const calls = serveList();
    const { history } = renderApp('/requests?page=3&sort=old', { me: demoInitiator });
    await screen.findByRole('table');

    await choose('Результат', 'Отказ');

    await waitFor(() => expect(history.location.search).toBe('?result=REJECTED&sort=old'));
    await waitFor(() => expect(calls.at(-1)?.get('businessStatus')).toBe('REJECTED'));
    expect(calls.at(-1)?.get('page')).toBe('0');
  });

  it('an operator filters by delivery', async () => {
    const calls = serveList();
    const { history } = renderApp('/requests', { me: demoOperator });
    await screen.findByRole('table');

    await choose('Доставка', 'Неизвестно, получена ли');

    await waitFor(() => expect(history.location.search).toBe('?delivery=UNKNOWN'));
    await waitFor(() => expect(calls.at(-1)?.get('deliveryStatus')).toBe('UNKNOWN'));
  });

  it('an initiator has no delivery filter', async () => {
    serveList();
    renderApp('/requests', { me: demoInitiator });
    await screen.findByRole('table');

    expect(screen.getByRole('combobox', { name: 'Результат' })).toBeInTheDocument();
    expect(screen.queryByRole('combobox', { name: 'Доставка' })).not.toBeInTheDocument();
  });

  it('"Только просроченные" asks for overdue requests only', async () => {
    const calls = serveList();
    const { history } = renderApp('/requests', { me: demoInitiator });
    await screen.findByRole('table');

    await userEvent.click(screen.getByRole('switch', { name: 'Только просроченные' }));

    await waitFor(() => expect(history.location.search).toBe('?overdue=1'));
    await waitFor(() => expect(calls.at(-1)?.get('overdue')).toBe('true'));
  });

  it('the sort goes into the address and the query, back on the first page', async () => {
    const calls = serveList();
    const { history } = renderApp('/requests?page=2', { me: demoInitiator });
    await screen.findByRole('table');

    await choose('Сортировка', 'По сроку');

    await waitFor(() => expect(history.location.search).toBe('?sort=deadline'));
    await waitFor(() => expect(calls.at(-1)?.get('sort')).toBe('deadline,asc'));
  });

  it('shows the active filters and resets them, keeping the sort', async () => {
    serveList();
    const { history } = renderApp('/requests?result=SUCCEEDED&overdue=1&sort=number', { me: demoOperator });
    await screen.findByRole('table');
    expect(screen.getByRole('combobox', { name: 'Результат' })).toHaveValue('Выполнена');
    expect(screen.getByRole('switch', { name: 'Только просроченные' })).toBeChecked();

    await userEvent.click(screen.getByRole('button', { name: 'Сбросить' }));

    await waitFor(() => expect(history.location.search).toBe('?sort=number'));
    expect(screen.queryByRole('button', { name: 'Сбросить' })).not.toBeInTheDocument();
  });

  it('says that nothing matches the filter and offers to reset it', async () => {
    serveList((query) => (query.get('businessStatus') ? listPage([]) : listPage([listItem({ number: 'DR-001001' })])));
    const { history } = renderApp('/requests?result=REJECTED', { me: demoInitiator });

    expect(await screen.findByText('Ничего не найдено')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Сбросить фильтры' }));

    expect(await screen.findByText('DR-001001')).toBeInTheDocument();
    expect(history.location.search).toBe('');
  });

  it('Back returns the previous filter', async () => {
    serveList();
    const { history } = renderApp('/requests', { me: demoInitiator });
    await screen.findByRole('table');
    await choose('Результат', 'Выполнена');
    await waitFor(() => expect(history.location.search).toBe('?result=SUCCEEDED'));

    history.back();

    await waitFor(() => expect(screen.getByRole('combobox', { name: 'Результат' })).toHaveValue('Все'));
    expect(history.location.search).toBe('');
  });
});
