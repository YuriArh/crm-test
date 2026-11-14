// @vitest-environment jsdom
import { notifications } from '@mantine/notifications';
import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { RequestCard, UpdateRequest } from '@/shared/api';
import { demoInitiator, problem, server } from '@/test/server';
import { renderApp } from '@/test/renderApp';
import { CARD_REFRESH_MS } from './api';
import { CARD_ID, cardPath, demoOperator, pendingCard, serveCard } from './testData';

/** The server's card; PATCH applies a body to it when the version matches, else answers 409 with it. */
function fakeServer(initial: RequestCard) {
  const state = { card: initial, bodies: [] as UpdateRequest[] };
  serveCard(() => state.card);
  server.use(
    http.patch(`*/api/requests/${CARD_ID}`, async ({ request }) => {
      const body = (await request.json()) as UpdateRequest;
      state.bodies.push(body);
      if (body.version !== state.card.version) {
        return problem(409, 'VERSION_CONFLICT', { current: state.card });
      }
      state.card = {
        ...state.card,
        ...('comment' in body ? { comment: body.comment ?? null } : {}),
        version: state.card.version + 1,
      };
      return HttpResponse.json(state.card);
    }),
  );
  return state;
}

const someoneElse = { id: demoOperator.id, displayName: 'Оператор (Север)' };

describe('conflicts', () => {
  afterEach(() => {
    notifications.clean();
    vi.useRealTimers();
  });

  it('resends silently when someone else changed another field', async () => {
    const backend = fakeServer(pendingCard({ version: 1, comment: null }));
    renderApp(cardPath, { me: demoInitiator });

    await userEvent.type(await screen.findByRole('textbox', { name: 'Комментарий' }), 'Мой');
    backend.card = { ...backend.card, assignee: someoneElse, version: 2 };
    await userEvent.click(screen.getByRole('button', { name: 'Сохранить' }));

    expect(await screen.findByText('Сохранено')).toBeInTheDocument();
    expect(backend.bodies).toEqual([
      { version: 1, comment: 'Мой' },
      { version: 2, comment: 'Мой' },
    ]);
    expect(backend.card).toMatchObject({ comment: 'Мой', assignee: someoneElse, version: 3 });
  });

  it('shows their value on the same field and keeps mine on request', async () => {
    const backend = fakeServer(pendingCard({ version: 1, comment: 'Исходный' }));
    renderApp(cardPath, { me: demoInitiator });

    const field = await screen.findByRole('textbox', { name: 'Комментарий' });
    await userEvent.clear(field);
    await userEvent.type(field, 'Мой');
    backend.card = { ...backend.card, comment: 'Их', version: 2 };
    await userEvent.click(screen.getByRole('button', { name: 'Сохранить' }));

    expect(await screen.findByText('Изменил другой пользователь:')).toBeInTheDocument();
    expect(screen.getByText('Их')).toBeInTheDocument();
    expect(field).toHaveValue('Мой');
    expect(screen.getByRole('button', { name: 'Сохранить' })).toBeDisabled();

    await userEvent.click(screen.getByRole('button', { name: 'Оставить моё' }));

    expect(await screen.findByText('Сохранено')).toBeInTheDocument();
    expect(backend.bodies.at(-1)).toEqual({ version: 2, comment: 'Мой' });
    expect(field).toHaveValue('Мой');
  });

  it('takes their value on request and sends nothing', async () => {
    const backend = fakeServer(pendingCard({ version: 1, comment: 'Исходный' }));
    renderApp(cardPath, { me: demoInitiator });

    const field = await screen.findByRole('textbox', { name: 'Комментарий' });
    await userEvent.type(field, ' мой');
    backend.card = { ...backend.card, comment: 'Их', version: 2 };
    await userEvent.click(screen.getByRole('button', { name: 'Сохранить' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Взять их' }));

    expect(field).toHaveValue('Их');
    expect(screen.queryByRole('button', { name: 'Сохранить' })).not.toBeInTheDocument();
    expect(backend.bodies).toHaveLength(1);
  });

  it('never lets a refresh overwrite the draft, and shows a change of the edited field at once', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const backend = fakeServer(pendingCard({ version: 1, comment: null }));
    renderApp(cardPath, { me: demoInitiator });

    const field = await screen.findByRole('textbox', { name: 'Комментарий' });
    await userEvent.type(field, 'Черновик');

    // Someone else assigns an operator: the refresh shows it and the draft stays.
    backend.card = { ...backend.card, assignee: someoneElse, version: 2 };
    await act(() => vi.advanceTimersByTimeAsync(CARD_REFRESH_MS + 100));
    expect(await screen.findByText('Оператор (Север)')).toBeInTheDocument();
    expect(field).toHaveValue('Черновик');
    expect(screen.queryByText('Изменил другой пользователь:')).not.toBeInTheDocument();

    // Someone else writes a comment: the conflict appears before any save.
    backend.card = { ...backend.card, comment: 'Их', version: 3 };
    await act(() => vi.advanceTimersByTimeAsync(CARD_REFRESH_MS + 100));
    expect(await screen.findByText('Изменил другой пользователь:')).toBeInTheDocument();
    expect(field).toHaveValue('Черновик');
    expect(backend.bodies).toEqual([]);
  });
});

describe('leaving with unsaved changes', () => {
  it('asks first, stays on «Остаться» and leaves on «Уйти»', async () => {
    fakeServer(pendingCard());
    const { history } = renderApp(cardPath, { me: demoInitiator });

    await userEvent.type(await screen.findByRole('textbox', { name: 'Комментарий' }), 'Не сохранено');
    await userEvent.click(screen.getByRole('link', { name: 'Заявки' }));

    expect(await screen.findByText('Уйти без сохранения?')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Остаться' }));
    await waitFor(() => expect(screen.queryByText('Уйти без сохранения?')).not.toBeInTheDocument());
    expect(history.location.pathname).toBe(cardPath);
    expect(screen.getByRole('textbox', { name: 'Комментарий' })).toHaveValue('Не сохранено');

    await userEvent.click(screen.getByRole('link', { name: 'Заявки' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Уйти' }));
    await waitFor(() => expect(history.location.pathname).toBe('/requests'));
  });

  it('does not ask without changes', async () => {
    fakeServer(pendingCard());
    const { history } = renderApp(cardPath, { me: demoInitiator });

    await userEvent.click(await screen.findByRole('link', { name: 'Заявки' }));

    await waitFor(() => expect(history.location.pathname).toBe('/requests'));
  });
});
