// @vitest-environment jsdom
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import type { RequestPage } from '@/shared/api';
import { demoInitiator, server } from '@/test/server';
import { renderApp } from '@/test/renderApp';
import { demoOperator, findRow, listItem, listPage, systemInfoHandler } from './fixtures';

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

/** The desktop filter bar; jsdom renders the phone toolbar too (a browser hides it with CSS). */
const bar = () => within(screen.getByTestId('filter-bar'));

async function choose(field: string, option: string, scope = bar) {
  await screen.findByRole('table');
  const input = scope().getByRole('combobox', { name: field });
  await userEvent.click(input);
  const listbox = document.getElementById(input.getAttribute('aria-controls') ?? '')!;
  // The dropdown is still in its opening transition, which jsdom never finishes: count it as visible.
  await userEvent.click(within(listbox).getByRole('option', { name: option, hidden: true }));
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

    expect(bar().getByRole('combobox', { name: 'Результат' })).toBeInTheDocument();
    expect(bar().queryByRole('combobox', { name: 'Доставка' })).not.toBeInTheDocument();
  });

  it('"Только просроченные" asks for overdue requests only', async () => {
    const calls = serveList();
    const { history } = renderApp('/requests', { me: demoInitiator });
    await screen.findByRole('table');

    await userEvent.click(bar().getByRole('switch', { name: 'Только просроченные' }));

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
    expect(bar().getByRole('combobox', { name: 'Результат' })).toHaveValue('Выполнена');
    expect(bar().getByRole('switch', { name: 'Только просроченные' })).toBeChecked();

    await userEvent.click(bar().getByRole('button', { name: 'Сбросить' }));

    await waitFor(() => expect(history.location.search).toBe('?sort=number'));
    expect(bar().queryByRole('button', { name: 'Сбросить' })).not.toBeInTheDocument();
  });

  it('says that nothing matches the filter and offers to reset it', async () => {
    serveList((query) => (query.get('businessStatus') ? listPage([]) : listPage([listItem({ number: 'DR-001001' })])));
    const { history } = renderApp('/requests?result=REJECTED', { me: demoInitiator });

    expect(await screen.findByText('Ничего не найдено')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Сбросить фильтры' }));

    expect(await findRow('DR-001001')).toBeInTheDocument();
    expect(history.location.search).toBe('');
  });

  it('Back returns the previous filter', async () => {
    serveList();
    const { history } = renderApp('/requests', { me: demoInitiator });
    await screen.findByRole('table');
    await choose('Результат', 'Выполнена');
    await waitFor(() => expect(history.location.search).toBe('?result=SUCCEEDED'));

    history.back();

    await waitFor(() => expect(bar().getByRole('combobox', { name: 'Результат' })).toHaveValue('Все'));
    expect(history.location.search).toBe('');
  });
});

describe('RequestFilterToolbar (phone)', () => {
  // jsdom is as wide as a desktop, so the phone toolbar is hidden by CSS: query it with `hidden: true`.
  const toolbar = () => within(screen.getByTestId('filter-toolbar'));
  // The drawer is still in its opening transition, which jsdom never finishes: count it as visible.
  const drawer = async () => within(await screen.findByRole('dialog', { name: 'Фильтры', hidden: true }));

  it('shows the active filters as chips; a chip removes its filter with one tap', async () => {
    serveList();
    const { history } = renderApp('/requests?result=REJECTED&overdue=1', { me: demoInitiator });
    await screen.findByRole('table');
    expect(toolbar().getByRole('button', { name: 'Фильтры · 2', hidden: true })).toBeInTheDocument();

    await userEvent.click(toolbar().getByRole('button', { name: 'Снять фильтр «Отказ»', hidden: true }));

    await waitFor(() => expect(history.location.search).toBe('?overdue=1'));
    expect(toolbar().getByRole('button', { name: 'Фильтры · 1', hidden: true })).toBeInTheDocument();
    expect(toolbar().getByRole('button', { name: 'Снять фильтр «Просроченные»', hidden: true })).toBeInTheDocument();
  });

  it('the drawer changes nothing until "Показать", then applies every field at once', async () => {
    const calls = serveList();
    const { history } = renderApp('/requests?page=2', { me: demoOperator });
    await screen.findByRole('table');

    await userEvent.click(toolbar().getByRole('button', { name: 'Фильтры', hidden: true }));
    const sheet = await drawer();
    const delivery = sheet.getByRole('combobox', { name: 'Доставка', hidden: true });
    await userEvent.click(delivery);
    const listbox = document.getElementById(delivery.getAttribute('aria-controls') ?? '')!;
    await userEvent.click(within(listbox).getByRole('option', { name: 'Ошибка отправки', hidden: true }));
    await userEvent.click(sheet.getByRole('switch', { name: 'Только просроченные', hidden: true }));
    expect(history.location.search).toBe('?page=2');

    await userEvent.click(sheet.getByRole('button', { name: 'Показать', hidden: true }));

    await waitFor(() => expect(history.location.search).toBe('?delivery=FAILED&overdue=1'));
    await waitFor(() => expect(calls.at(-1)?.get('deliveryStatus')).toBe('FAILED'));
    expect(calls.at(-1)?.get('overdue')).toBe('true');
  });

  it('"Сбросить" in the drawer clears every filter', async () => {
    serveList();
    const { history } = renderApp('/requests?result=SUCCEEDED&overdue=1&sort=old', { me: demoInitiator });
    await screen.findByRole('table');

    await userEvent.click(toolbar().getByRole('button', { name: 'Фильтры · 2', hidden: true }));
    await userEvent.click((await drawer()).getByRole('button', { name: 'Сбросить', hidden: true }));

    await waitFor(() => expect(history.location.search).toBe('?sort=old'));
  });
});
