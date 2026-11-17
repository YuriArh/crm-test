// @vitest-environment jsdom
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { CARD_ID, cardPath, demoOperator, pendingCard, serveCard, serveOperators, succeededCard, withExchange } from '@/requests/card/testData';
import type { ExchangeProblem, RequestCard, UpdateRequest } from '@/shared/api';
import { problem, server } from '@/test/server';
import { renderApp } from '@/test/renderApp';
import { externalMissing, invalidMessage, reconciliationConflict, resultConflict } from '../fixtures';

type User = ReturnType<typeof userEvent.setup>;

// One problem per kind for the whole file: every fixture call makes a new id.
const conflict = resultConflict();
const record = invalidMessage();
const reconciliation = reconciliationConflict();
const lost = externalMissing({
  currentResult: {
    businessStatus: 'SUCCEEDED',
    result: { trackingNumber: 'TRK-00000001', price: 325, rejectionReason: null, resultAt: '2026-11-17T11:00:00Z' },
  },
  actions: ['KEEP_OURS'],
});
const operator = { id: demoOperator.id, displayName: 'Оператор (Север)' };

function closed(p: ExchangeProblem, decision: ExchangeProblem['decision'], comment: string): ExchangeProblem {
  return { ...p, status: 'RESOLVED', resolvedAt: '2026-11-17T11:30:00Z', resolvedBy: operator, decision, comment, actions: [] };
}

/** Answers `resolve` with `answers` in turn (the last repeats); records bodies and keys. */
function serveResolve(id: string, ...answers: ((body: { decision: string; comment: string }) => Response)[]) {
  const calls: { key: string | null; body: { decision: string; comment: string } }[] = [];
  server.use(
    http.post(`*/api/problems/${id}/resolve`, async ({ request }) => {
      const body = (await request.json()) as { decision: string; comment: string };
      calls.push({ key: request.headers.get('Idempotency-Key'), body });
      return answers[Math.min(calls.length - 1, answers.length - 1)]!(body);
    }),
  );
  return calls;
}

async function openDecision(user: User, caption: string) {
  const block = await screen.findByRole('region', { name: 'Проблемы' });
  await user.click(within(block).getByRole('button', { name: caption }));
  return screen.findByRole('dialog', { name: caption });
}

describe('deciding on a problem', () => {
  it('names the decision, shows both versions of a conflict and what will change', async () => {
    const user = userEvent.setup();
    serveCard(withExchange(succeededCard({ problems: [conflict] })));
    serveOperators();
    renderApp(cardPath, { me: demoOperator });

    const dialog = await openDecision(user, 'Принять версию перевозчика');
    expect(within(dialog).getByText(/Итог заявки будет заменён версией перевозчика/)).toBeInTheDocument();
    expect(within(dialog).getByRole('region', { name: 'Версия перевозчика' })).toBeInTheDocument();
    expect(within(dialog).getByRole('region', { name: 'Наш итог при обнаружении' })).toBeInTheDocument();
  });

  it('keeping ours in a reconciliation conflict says reconciliation remembers it, and shows both versions', async () => {
    const user = userEvent.setup();
    serveCard(withExchange(succeededCard({ problems: [reconciliation] })));
    serveOperators();
    renderApp(cardPath, { me: demoOperator });

    const dialog = await openDecision(user, 'Оставить наш итог');
    expect(dialog).toHaveTextContent('Итог заявки не изменится.');
    expect(dialog).toHaveTextContent(
      'Сверка запомнит решение: этот же ответ перевозчика проблему больше не откроет, другой ответ или смена нашего итога — откроет снова',
    );
    expect(within(dialog).getByRole('region', { name: 'Версия перевозчика' })).toBeInTheDocument();
    expect(within(dialog).getByRole('region', { name: 'Наш итог при обнаружении' })).toBeInTheDocument();
  });

  it('keeping ours for a lost operation says the operator sorts it out with the carrier', async () => {
    const user = userEvent.setup();
    serveCard(withExchange(succeededCard({ problems: [lost] })));
    serveOperators();
    renderApp(cardPath, { me: demoOperator });

    const dialog = await openDecision(user, 'Оставить наш итог');
    expect(dialog).toHaveTextContent('Итог заявки не изменится.');
    expect(dialog).toHaveTextContent('Потерю операции перевозчиком вы разбираете с ним сами');
  });

  it('keeping ours in a feed conflict says only that the result stays', async () => {
    const user = userEvent.setup();
    serveCard(withExchange(succeededCard({ problems: [conflict] })));
    serveOperators();
    renderApp(cardPath, { me: demoOperator });

    const dialog = await openDecision(user, 'Оставить наш итог');
    expect(dialog).toHaveTextContent('Итог заявки не изменится.');
    expect(dialog).not.toHaveTextContent('Сверка запомнит');
    expect(dialog).not.toHaveTextContent('Потерю операции');
  });

  it('does not send without a comment', async () => {
    const user = userEvent.setup();
    serveCard(withExchange(pendingCard({ problems: [record] })));
    serveOperators();
    const calls = serveResolve(record.id, () => HttpResponse.json({}));
    renderApp(cardPath, { me: demoOperator });

    const dialog = await openDecision(user, 'Разобрано');
    expect(within(dialog).getByText('Проблема закроется, заявка не изменится.')).toBeInTheDocument();
    await user.type(within(dialog).getByRole('textbox', { name: 'Комментарий' }), '   ');
    await user.click(within(dialog).getByRole('button', { name: 'Сохранить решение' }));

    expect(await within(dialog).findByText('Опишите основание решения')).toBeInTheDocument();
    expect(calls).toHaveLength(0);
  });

  it('sends the decision with a key, closes, says so and asks the card again', async () => {
    const user = userEvent.setup();
    let card: RequestCard = withExchange(succeededCard({ problems: [conflict] }));
    const cardCalls = serveCard(() => card);
    serveOperators();
    const calls = serveResolve(conflict.id, (body) => {
      const after = closed(conflict, 'KEEP_OURS', body.comment);
      card = { ...card, problems: [after] };
      return HttpResponse.json(after);
    });
    renderApp(cardPath, { me: demoOperator });

    const dialog = await openDecision(user, 'Оставить наш итог');
    expect(within(dialog).getByText('Итог заявки не изменится.')).toBeInTheDocument();
    await user.type(within(dialog).getByRole('textbox', { name: 'Комментарий' }), '  Проверено с перевозчиком ');
    const before = cardCalls.count;
    await user.click(within(dialog).getByRole('button', { name: 'Сохранить решение' }));

    expect(await screen.findByText('Решение сохранено')).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(calls).toHaveLength(1);
    expect(calls[0]!.body).toEqual({ decision: 'KEEP_OURS', comment: 'Проверено с перевозчиком' });
    expect(calls[0]!.key).toMatch(/^[0-9a-f-]{36}$/);
    await waitFor(() => expect(cardCalls.count).toBeGreaterThan(before));
    expect(await screen.findByRole('button', { name: 'Закрытые проблемы (1)' })).toBeInTheDocument();
  });

  it('keeps the comment after a lost answer and repeats with the same key; a changed comment is a new key', async () => {
    const user = userEvent.setup();
    serveCard(withExchange(pendingCard({ problems: [record] })));
    serveOperators();
    const calls = serveResolve(
      record.id,
      () => HttpResponse.error(),
      () => HttpResponse.error(),
      () => problem(503, 'HTTP_503'),
    );
    renderApp(cardPath, { me: demoOperator });

    const dialog = await openDecision(user, 'Разобрано');
    const comment = within(dialog).getByRole('textbox', { name: 'Комментарий' });
    await user.type(comment, 'Битая цена');
    await user.click(within(dialog).getByRole('button', { name: 'Сохранить решение' }));
    expect(await within(dialog).findByText(/повтор безопасен/)).toBeInTheDocument();
    expect(comment).toHaveValue('Битая цена');
    await user.click(within(dialog).getByRole('button', { name: 'Сохранить решение' }));
    await waitFor(() => expect(calls).toHaveLength(2));
    await user.type(comment, '!');
    await user.click(within(dialog).getByRole('button', { name: 'Сохранить решение' }));
    await waitFor(() => expect(calls).toHaveLength(3));

    expect(calls[1]!.key).toBe(calls[0]!.key);
    expect(calls[2]!.key).not.toBe(calls[0]!.key);
  });

  it('says the decision is no longer possible, asks again and closes on «Понятно»', async () => {
    const user = userEvent.setup();
    const cardCalls = serveCard(withExchange(pendingCard({ problems: [record] })));
    serveOperators();
    serveResolve(record.id, () => problem(409, 'INVALID_STATE'));
    renderApp(cardPath, { me: demoOperator });

    const dialog = await openDecision(user, 'Разобрано');
    await user.type(within(dialog).getByRole('textbox', { name: 'Комментарий' }), 'Битая цена');
    const before = cardCalls.count;
    await user.click(within(dialog).getByRole('button', { name: 'Сохранить решение' }));

    expect(await within(dialog).findByText('Решение уже недоступно: проблему закрыли или её состояние изменилось')).toBeInTheDocument();
    await waitFor(() => expect(cardCalls.count).toBeGreaterThan(before));
    await user.click(within(dialog).getByRole('button', { name: 'Понятно' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('shows a refused comment under the field in its own words', async () => {
    const user = userEvent.setup();
    serveCard(withExchange(pendingCard({ problems: [record] })));
    serveOperators();
    serveResolve(record.id, () =>
      problem(400, 'VALIDATION_FAILED', { errors: [{ field: 'comment', message: 'must not be blank' }] }),
    );
    renderApp(cardPath, { me: demoOperator });

    const dialog = await openDecision(user, 'Разобрано');
    await user.type(within(dialog).getByRole('textbox', { name: 'Комментарий' }), 'x');
    await user.click(within(dialog).getByRole('button', { name: 'Сохранить решение' }));
    expect(await within(dialog).findByText('Опишите основание решения')).toBeInTheDocument();
    expect(within(dialog).queryByText('must not be blank')).not.toBeInTheDocument();
  });

  it('says a problem is unavailable when it left the scope', async () => {
    const user = userEvent.setup();
    serveCard(withExchange(pendingCard({ problems: [record] })));
    serveOperators();
    serveResolve(record.id, () => problem(404, 'NOT_FOUND'));
    renderApp(cardPath, { me: demoOperator });

    const dialog = await openDecision(user, 'Разобрано');
    await user.type(within(dialog).getByRole('textbox', { name: 'Комментарий' }), 'x');
    await user.click(within(dialog).getByRole('button', { name: 'Сохранить решение' }));
    expect(await within(dialog).findByText('Проблема недоступна')).toBeInTheDocument();
  });

  it('an edit started before the carrier version was applied is resent silently on the new version', async () => {
    const user = userEvent.setup();
    const state = { card: withExchange(succeededCard({ version: 3, problems: [conflict] })), patches: [] as UpdateRequest[] };
    serveCard(() => state.card);
    serveOperators();
    server.use(
      http.patch(`*/api/requests/${CARD_ID}`, async ({ request }) => {
        const body = (await request.json()) as UpdateRequest;
        state.patches.push(body);
        if (body.version !== state.card.version) return problem(409, 'VERSION_CONFLICT', { current: state.card });
        state.card = { ...state.card, comment: body.comment ?? null, version: state.card.version + 1 };
        return HttpResponse.json(state.card);
      }),
    );
    serveResolve(conflict.id, (body) => {
      const after = closed(conflict, 'APPLY_CARRIER', body.comment);
      // APPLY_CARRIER replaces the result and bumps the version.
      state.card = {
        ...state.card,
        result: { trackingNumber: 'TRK-00000002', price: 400, rejectionReason: null, resultAt: '2026-11-17T11:06:00Z' },
        version: state.card.version + 1,
        problems: [after],
      };
      return HttpResponse.json(after);
    });
    renderApp(cardPath, { me: demoOperator });

    await user.type(await screen.findByRole('textbox', { name: 'Комментарий' }), 'Мой');
    const dialog = await openDecision(user, 'Принять версию перевозчика');
    await user.type(within(dialog).getByRole('textbox', { name: 'Комментарий' }), 'Перевозчик прав');
    await user.click(within(dialog).getByRole('button', { name: 'Сохранить решение' }));
    await screen.findByText('Решение сохранено');

    await user.click(screen.getByRole('button', { name: 'Сохранить' }));
    expect(await screen.findByText('Сохранено')).toBeInTheDocument();
    expect(state.patches.at(-1)).toEqual({ version: 4, comment: 'Мой' });
    expect(state.card).toMatchObject({ comment: 'Мой', version: 5 });
  });
});
