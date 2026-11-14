// @vitest-environment jsdom
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import type { RequestCard, UpdateRequest } from '@/shared/api';
import { demoInitiator, problem, server } from '@/test/server';
import { renderApp } from '@/test/renderApp';
import { CARD_ID, cardPath, pendingCard, serveCard, succeededCard } from './testData';

/** Answers `PATCH` with `answer(body)` and records every body. */
function servePatch(answer: (body: UpdateRequest) => Response) {
  const bodies: UpdateRequest[] = [];
  server.use(
    http.patch(`*/api/requests/${CARD_ID}`, async ({ request }) => {
      const body = (await request.json()) as UpdateRequest;
      bodies.push(body);
      return answer(body);
    }),
  );
  return bodies;
}

function saved(card: RequestCard, body: UpdateRequest): Response {
  return HttpResponse.json({ ...card, comment: body.comment ?? null, version: card.version + 1 });
}

describe('comment', () => {
  // The notifications store outlives a test's render.

  it('saves only the comment with the version the edit started from', async () => {
    const card = succeededCard({ comment: 'Позвонить за час', version: 3 });
    serveCard(card);
    const bodies = servePatch((body) => saved(card, body));
    renderApp(cardPath, { me: demoInitiator });

    const field = await screen.findByRole('textbox', { name: 'Комментарий' });
    expect(field).toHaveValue('Позвонить за час');
    expect(screen.queryByRole('button', { name: 'Сохранить' })).not.toBeInTheDocument();

    await userEvent.clear(field);
    await userEvent.type(field, 'Позвонить за два часа');
    await userEvent.click(screen.getByRole('button', { name: 'Сохранить' }));

    expect(await screen.findByText('Сохранено')).toBeInTheDocument();
    expect(bodies).toEqual([{ version: 3, comment: 'Позвонить за два часа' }]);
    expect(field).toHaveValue('Позвонить за два часа');
    expect(screen.queryByRole('button', { name: 'Сохранить' })).not.toBeInTheDocument();
  });

  it('cancels back to the value of the card without asking the server', async () => {
    serveCard(succeededCard({ comment: 'Позвонить за час' }));
    const bodies = servePatch(() => HttpResponse.json(succeededCard()));
    renderApp(cardPath, { me: demoInitiator });

    const field = await screen.findByRole('textbox', { name: 'Комментарий' });
    await userEvent.type(field, ' и ещё');
    await userEvent.click(screen.getByRole('button', { name: 'Отменить' }));

    expect(field).toHaveValue('Позвонить за час');
    expect(screen.queryByRole('button', { name: 'Отменить' })).not.toBeInTheDocument();
    expect(bodies).toEqual([]);
  });

  it('removes the comment when it is emptied', async () => {
    const card = succeededCard({ comment: 'Позвонить за час' });
    serveCard(card);
    const bodies = servePatch((body) => saved(card, body));
    renderApp(cardPath, { me: demoInitiator });

    await userEvent.clear(await screen.findByRole('textbox', { name: 'Комментарий' }));
    await userEvent.click(screen.getByRole('button', { name: 'Сохранить' }));

    await waitFor(() => expect(bodies).toEqual([{ version: 3, comment: null }]));
    expect(await screen.findByRole('textbox', { name: 'Комментарий' })).toHaveValue('');
  });

  it('keeps the text after a failed save and saves it on a repeat', async () => {
    const card = succeededCard({ comment: null });
    serveCard(card);
    let fail = true;
    servePatch((body) => (fail ? HttpResponse.error() : saved(card, body)));
    renderApp(cardPath, { me: demoInitiator });

    const field = await screen.findByRole('textbox', { name: 'Комментарий' });
    await userEvent.type(field, 'Хрупкое');
    await userEvent.click(screen.getByRole('button', { name: 'Сохранить' }));

    expect(await screen.findByText('Не удалось сохранить, повторите')).toBeInTheDocument();
    expect(field).toHaveValue('Хрупкое');

    fail = false;
    await userEvent.click(screen.getByRole('button', { name: 'Сохранить' }));
    expect(await screen.findByText('Сохранено')).toBeInTheDocument();
  });

  it('says so when a server failure (5xx) stops the save', async () => {
    serveCard(succeededCard());
    servePatch(() => problem(500, 'INTERNAL_ERROR'));
    renderApp(cardPath, { me: demoInitiator });

    await userEvent.type(await screen.findByRole('textbox', { name: 'Комментарий' }), 'Текст');
    await userEvent.click(screen.getByRole('button', { name: 'Сохранить' }));

    expect(await screen.findByText('Не удалось сохранить, повторите')).toBeInTheDocument();
  });

  it('explains a 403', async () => {
    serveCard(succeededCard());
    servePatch(() => problem(403, 'FORBIDDEN'));
    renderApp(cardPath, { me: demoInitiator });

    await userEvent.type(await screen.findByRole('textbox', { name: 'Комментарий' }), 'Текст');
    await userEvent.click(screen.getByRole('button', { name: 'Сохранить' }));

    expect(await screen.findByText('Недостаточно прав для этого изменения')).toBeInTheDocument();
  });

  it('says "Заявка не найдена" when the request has left the scope', async () => {
    let gone = false;
    server.use(
      http.get('*/api/system/info', () =>
        HttpResponse.json({ application: 'dispatch', serverTime: '2026-11-14T12:00:00Z', businessTimezone: 'Europe/Moscow' }),
      ),
      http.get(`*/api/requests/${CARD_ID}`, () => (gone ? problem(404, 'NOT_FOUND') : HttpResponse.json(pendingCard()))),
    );
    servePatch(() => {
      gone = true;
      return problem(404, 'NOT_FOUND');
    });
    renderApp(cardPath, { me: demoInitiator });

    await userEvent.type(await screen.findByRole('textbox', { name: 'Комментарий' }), 'Текст');
    await userEvent.click(screen.getByRole('button', { name: 'Сохранить' }));

    expect(await screen.findByRole('heading', { name: 'Заявка не найдена' })).toBeInTheDocument();
  });

  it('shows a counter only close to the limit', async () => {
    serveCard(succeededCard({ comment: 'а'.repeat(1799) }));
    renderApp(cardPath, { me: demoInitiator });

    const field = await screen.findByRole('textbox', { name: 'Комментарий' });
    expect(screen.queryByText(/\/ 2000/)).not.toBeInTheDocument();

    await userEvent.type(field, 'б');
    expect(screen.getByText('1800 / 2000')).toBeInTheDocument();
    expect(field).toHaveAttribute('maxlength', '2000');
  });
});
