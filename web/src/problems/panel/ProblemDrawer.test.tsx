// @vitest-environment jsdom
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { demoOperator } from '@/requests/card/testData';
import { systemInfoHandler } from '@/requests/list/fixtures';
import type { ExchangeProblem } from '@/shared/api';
import { demoInitiator, problem as problemResponse, server } from '@/test/server';
import { renderApp } from '@/test/renderApp';
import { deliveryFailed, invalidMessage, problemPage } from '../fixtures';

const record = invalidMessage();
const other = deliveryFailed();

/** The list (always `record` and `other`) and one problem by id (`one()`); counts the list requests. */
function serve(one: () => Response) {
  const calls = { list: 0, one: 0 };
  server.use(
    systemInfoHandler,
    http.get('*/api/problems', () => {
      calls.list += 1;
      return HttpResponse.json(problemPage([record, other]));
    }),
    http.get('*/api/problems/:id', () => {
      calls.one += 1;
      return one();
    }),
  );
  return calls;
}

describe('problem panel', () => {
  it('opens from a row of the list, keeping the list’s address, and loads the problem by id', async () => {
    const user = userEvent.setup();
    serve(() => HttpResponse.json(record));
    const { router } = renderApp('/problems?status=all', { me: demoOperator });

    const table = await screen.findByRole('table');
    await user.click(within(table).getAllByRole('link', { name: 'Некорректное сообщение перевозчика' })[0]!);

    const panel = await screen.findByRole('dialog', { name: 'Некорректное сообщение перевозчика' });
    expect(router.state.location.pathname).toBe(`/problems/${record.id}`);
    expect(router.state.location.search).toEqual({ status: 'all' });
    expect(await within(panel).findByRole('region', { name: 'Сообщение перевозчика' })).toBeInTheDocument();
  });

  it('opens from the keyboard: Tab to the row, Enter; the decision dialog starts in the comment', async () => {
    const user = userEvent.setup();
    serve(() => HttpResponse.json(record));
    const { router } = renderApp('/problems', { me: demoOperator });

    const table = await screen.findByRole('table');
    within(table).getAllByRole('link', { name: 'Некорректное сообщение перевозчика' })[0]!.focus();
    await user.keyboard('{Enter}');
    await waitFor(() => expect(router.state.location.pathname).toBe(`/problems/${record.id}`));

    const panel = await screen.findByRole('dialog', { name: 'Некорректное сообщение перевозчика' });
    within(panel).getByRole('button', { name: 'Разобрано' }).focus();
    await user.keyboard('{Enter}');
    const dialog = await screen.findByRole('dialog', { name: 'Разобрано' });
    await waitFor(() => expect(within(dialog).getByRole('textbox', { name: 'Комментарий' })).toHaveFocus());
  });

  it('opens by a link to a problem that is not on the current page', async () => {
    const elsewhere = deliveryFailed({ details: 'HTTP 422' });
    serve(() => HttpResponse.json(elsewhere));
    renderApp(`/problems/${elsewhere.id}`, { me: demoOperator });

    const panel = await screen.findByRole('dialog', { name: 'Отправка не удалась' });
    expect(within(panel).getByText('HTTP 422')).toBeInTheDocument();
  });

  it.each([404, 403])('says the problem is not found or unavailable on %i, with the list still there', async (status) => {
    serve(() => problemResponse(status, status === 404 ? 'NOT_FOUND' : 'FORBIDDEN'));
    renderApp(`/problems/${record.id}`, { me: demoOperator });

    const panel = await screen.findByRole('dialog', { name: 'Проблема' });
    expect(await within(panel).findByText('Проблема не найдена или недоступна')).toBeInTheDocument();
    expect(screen.getByRole('table')).toBeInTheDocument();
  });

  it('says it could not load the problem and asks again on «Повторить»', async () => {
    const user = userEvent.setup();
    let fail = true;
    serve(() => (fail ? HttpResponse.error() : HttpResponse.json(record)));
    renderApp(`/problems/${record.id}`, { me: demoOperator });

    const panel = await screen.findByRole('dialog', { name: 'Проблема' });
    expect(await within(panel).findByText('Не удалось загрузить проблему', {}, { timeout: 5000 })).toBeInTheDocument();
    fail = false;
    await user.click(within(panel).getByRole('button', { name: 'Повторить' }));
    expect(await screen.findByRole('dialog', { name: 'Некорректное сообщение перевозчика' })).toBeInTheDocument();
  });

  it('stays open after a decision and shows the closed problem without decisions; the list is asked again', async () => {
    const user = userEvent.setup();
    let current: ExchangeProblem = record;
    const calls = serve(() => HttpResponse.json(current));
    server.use(
      http.post(`*/api/problems/${record.id}/resolve`, async ({ request }) => {
        const body = (await request.json()) as { comment: string };
        current = {
          ...record,
          status: 'RESOLVED',
          resolvedAt: '2026-11-17T11:30:00Z',
          resolvedBy: { id: demoOperator.id, displayName: 'Оператор (Север)' },
          decision: 'ACKNOWLEDGED',
          comment: body.comment,
          actions: [],
        };
        return HttpResponse.json(current);
      }),
    );
    renderApp(`/problems/${record.id}`, { me: demoOperator });

    const panel = await screen.findByRole('dialog', { name: 'Некорректное сообщение перевозчика' });
    await user.click(within(panel).getByRole('button', { name: 'Разобрано' }));
    const dialog = await screen.findByRole('dialog', { name: 'Разобрано' });
    await user.type(within(dialog).getByRole('textbox', { name: 'Комментарий' }), 'Битая цена');
    const before = calls.list;
    await user.click(within(dialog).getByRole('button', { name: 'Сохранить решение' }));

    expect(await within(panel).findByText(/Оператор \(Север\) · Разобрано/)).toBeInTheDocument();
    expect(within(panel).getByText('Битая цена')).toBeInTheDocument();
    expect(within(panel).queryByRole('button', { name: 'Разобрано' })).not.toBeInTheDocument();
    await waitFor(() => expect(calls.list).toBeGreaterThan(before));
  });

  it('closes back to the list with its address', async () => {
    const user = userEvent.setup();
    serve(() => HttpResponse.json(record));
    const { router } = renderApp(`/problems/${record.id}?status=resolved`, { me: demoOperator });

    const panel = await screen.findByRole('dialog', { name: 'Некорректное сообщение перевозчика' });
    await user.click(within(panel).getByRole('button', { name: 'Закрыть' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/problems'));
    expect(router.state.location.search).toEqual({ status: 'resolved' });
  });

  it('is not there for initiators', async () => {
    renderApp(`/problems/${record.id}`, { me: demoInitiator });
    expect(await screen.findByRole('heading', { name: 'Страница не найдена' })).toBeInTheDocument();
  });
});
