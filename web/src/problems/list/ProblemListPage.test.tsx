// @vitest-environment jsdom
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { demoOperator } from '@/requests/card/testData';
import { systemInfoHandler } from '@/requests/list/fixtures';
import type { ExchangeProblemPage } from '@/shared/api';
import { demoInitiator, server } from '@/test/server';
import { renderApp } from '@/test/renderApp';
import { deliveryFailed, invalidMessage, orphanMessage, problemPage, REQUEST_REF, resolvedByOperator } from '../fixtures';

/** Serves the list (`page` for any query) and records the query strings it got. */
function serveList(page: ExchangeProblemPage | (() => Response)) {
  const queries: URLSearchParams[] = [];
  server.use(
    systemInfoHandler,
    http.get('*/api/problems', ({ request }) => {
      queries.push(new URL(request.url).searchParams);
      return typeof page === 'function' ? page() : HttpResponse.json(page);
    }),
  );
  return queries;
}

describe('problems list', () => {
  it('shows the open problems of the service, newest first as dispatch sends them', async () => {
    const queries = serveList(problemPage([invalidMessage(), deliveryFailed(), orphanMessage()]));
    renderApp('/problems', { me: demoOperator });

    expect(await screen.findByRole('heading', { name: 'Проблемы', level: 2 })).toBeInTheDocument();
    const table = await screen.findByRole('table');
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows).toHaveLength(3);
    expect(within(rows[0]!).getByText('Некорректное сообщение перевозчика')).toBeInTheDocument();
    expect(within(rows[0]!).getByRole('link', { name: REQUEST_REF.number })).toBeInTheDocument();
    expect(within(rows[2]!).getByText('—')).toBeInTheDocument();
    expect(queries[0]!.get('status')).toBe('OPEN');
    expect(queries[0]!.get('size')).toBe('20');
  });

  it('shows who closed a resolved problem and how', async () => {
    serveList(problemPage([resolvedByOperator()]));
    renderApp('/problems?status=resolved', { me: demoOperator });

    const table = await screen.findByRole('table');
    expect(within(table).getByText('Оставлен наш итог · Оператор (Север)')).toBeInTheDocument();
  });

  it('puts the status and the kinds into the address and the query, back on page 1', async () => {
    const user = userEvent.setup();
    const queries = serveList(problemPage([deliveryFailed()], { totalElements: 45 }));
    const { router } = renderApp('/problems?page=2', { me: demoOperator });

    await screen.findByRole('table');
    await user.click(screen.getAllByRole('radio', { name: 'Все' })[0]!);
    await waitFor(() => expect(router.state.location.search).toEqual({ status: 'all' }));
    await user.click(screen.getAllByRole('combobox', { name: 'Вид' })[0]!);
    await user.click(await screen.findByRole('option', { name: 'Отправка не удалась' }));
    await waitFor(() => expect(router.state.location.search).toEqual({ status: 'all', kind: ['DELIVERY_FAILED'] }));
    await waitFor(() => expect(queries.at(-1)!.getAll('kind')).toEqual(['DELIVERY_FAILED']));
    expect(queries.at(-1)!.get('status')).toBe('ALL');
    expect(queries.at(-1)!.get('page')).toBe('0');
  });

  it('says there is nothing to do when no problem is open', async () => {
    serveList(problemPage([]));
    renderApp('/problems', { me: demoOperator });

    expect(await screen.findByText('Открытых проблем нет. Обмен идёт без вмешательства')).toBeInTheDocument();
  });

  it('says nothing was found under a filter and offers to reset it', async () => {
    const user = userEvent.setup();
    serveList(problemPage([]));
    const { router } = renderApp('/problems?status=resolved', { me: demoOperator });

    expect(await screen.findByText('Ничего не найдено')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Сбросить фильтры' }));
    await waitFor(() => expect(router.state.location.search).toEqual({}));
  });

  it('says the list could not be loaded and asks again on «Повторить»', async () => {
    const user = userEvent.setup();
    let fail = true;
    serveList(() => (fail ? HttpResponse.error() : HttpResponse.json(problemPage([deliveryFailed()]))));
    renderApp('/problems', { me: demoOperator });

    expect(await screen.findByText('Не удалось загрузить проблемы. Проверьте связь', {}, { timeout: 5000 })).toBeInTheDocument();
    fail = false;
    await user.click(screen.getByRole('button', { name: 'Повторить' }));
    expect(await screen.findByRole('table')).toBeInTheDocument();
  });

  it('is not there for initiators', async () => {
    renderApp('/problems', { me: demoInitiator });
    expect(await screen.findByRole('heading', { name: 'Страница не найдена' })).toBeInTheDocument();
  });
});
