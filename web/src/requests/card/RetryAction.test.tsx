// @vitest-environment jsdom
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { deliveryFailed, deliveryUnknown, externalMissing, reconciliationConflict } from '@/problems/fixtures';
import type { RequestCard } from '@/shared/api';
import { problem, server } from '@/test/server';
import { renderApp } from '@/test/renderApp';
import { CARD_ID, cardPath, demoOperator, pendingCard, serveCard, serveOperators, withExchange } from './testData';

function failedCard(overrides: Partial<RequestCard> = {}): RequestCard {
  return withExchange(
    pendingCard({ businessStatus: 'AWAITING', deliveryStatus: 'FAILED', actions: ['RETRY'], problems: [deliveryFailed()], ...overrides }),
  );
}

/** Answers `POST …/retry` with `answers` in turn (the last one repeats) and records the keys it got. */
function serveRetry(...answers: (() => Response)[]) {
  const keys: (string | null)[] = [];
  server.use(
    http.post(`*/api/requests/${CARD_ID}/retry`, ({ request }) => {
      keys.push(request.headers.get('Idempotency-Key'));
      return answers[Math.min(keys.length - 1, answers.length - 1)]!();
    }),
  );
  return keys;
}

async function openRetry(user: ReturnType<typeof userEvent.setup>) {
  const exchange = await screen.findByRole('region', { name: 'Обмен с перевозчиком' });
  await user.click(within(exchange).getByRole('button', { name: 'Повторить отправку' }));
  return screen.findByRole('dialog', { name: 'Повторить отправку заявки DR-000123?' });
}

describe('retrying the delivery', () => {
  it('is offered only when dispatch allows it: in the exchange panel and in the delivery problem', async () => {
    serveCard(failedCard());
    serveOperators();
    renderApp(cardPath, { me: demoOperator });

    const exchange = await screen.findByRole('region', { name: 'Обмен с перевозчиком' });
    expect(within(exchange).getByRole('button', { name: 'Повторить отправку' })).toBeInTheDocument();
    const problems = screen.getByRole('region', { name: 'Проблемы' });
    expect(within(problems).getByRole('button', { name: 'Повторить отправку' })).toBeInTheDocument();
  });

  it('is not offered without RETRY', async () => {
    serveCard(failedCard({ actions: [] }));
    serveOperators();
    renderApp(cardPath, { me: demoOperator });

    await screen.findByRole('region', { name: 'Обмен с перевозчиком' });
    expect(screen.queryByRole('button', { name: 'Повторить отправку' })).not.toBeInTheDocument();
  });

  it('explains a retry of a failed delivery and of an unknown one differently', async () => {
    const user = userEvent.setup();
    serveCard(failedCard());
    serveOperators();
    renderApp(cardPath, { me: demoOperator });
    expect(await openRetry(user)).toHaveTextContent('Начнётся новая серия попыток');
  });

  it('says an unknown delivery stays a problem until the carrier confirms it', async () => {
    const user = userEvent.setup();
    serveCard(failedCard({ deliveryStatus: 'UNKNOWN', problems: [deliveryUnknown()] }));
    serveOperators();
    renderApp(cardPath, { me: demoOperator });
    expect(await openRetry(user)).toHaveTextContent('останется открытой, пока перевозчик не подтвердит получение');
  });

  it('offers a retry in the notice of an operation the carrier lost, and explains it is sent again as it was', async () => {
    const user = userEvent.setup();
    serveCard(failedCard({ deliveryStatus: 'ACKNOWLEDGED', problems: [externalMissing()] }));
    serveOperators();
    renderApp(cardPath, { me: demoOperator });

    const problems = await screen.findByRole('region', { name: 'Проблемы' });
    await user.click(within(problems).getByRole('button', { name: 'Повторить отправку' }));
    const dialog = await screen.findByRole('dialog', { name: 'Повторить отправку заявки DR-000123?' });
    expect(dialog).toHaveTextContent('Перевозчик потерял операцию. Отправим её заново с тем же идентификатором');
  });

  it('offers no retry in the notice of a lost operation once dispatch stops offering it (we have a result)', async () => {
    serveCard(failedCard({ deliveryStatus: 'ACKNOWLEDGED', actions: [], problems: [externalMissing({ actions: ['KEEP_OURS'] })] }));
    serveOperators();
    renderApp(cardPath, { me: demoOperator });

    const problems = await screen.findByRole('region', { name: 'Проблемы' });
    expect(within(problems).queryByRole('button', { name: 'Повторить отправку' })).not.toBeInTheDocument();
  });

  it('never puts «Сверить сейчас» into a problem’s notice: it lives in the exchange panel only', async () => {
    serveCard(failedCard({ deliveryStatus: 'UNKNOWN', problems: [deliveryUnknown(), externalMissing(), reconciliationConflict()] }));
    serveOperators();
    renderApp(cardPath, { me: demoOperator });

    const problems = await screen.findByRole('region', { name: 'Проблемы' });
    expect(within(problems).queryByRole('button', { name: 'Сверить сейчас' })).not.toBeInTheDocument();
  });

  it('retries with a key, shows the card from the answer and says so', async () => {
    const user = userEvent.setup();
    serveCard(failedCard());
    serveOperators();
    const keys = serveRetry(() =>
      HttpResponse.json(failedCard({ deliveryStatus: 'PENDING', actions: [], problems: [deliveryFailed({ status: 'RESOLVED', decision: 'RETRIED', resolvedAt: '2026-11-17T11:20:00Z', resolvedBy: { id: demoOperator.id, displayName: 'Оператор (Север)' } })] })),
    );
    renderApp(cardPath, { me: demoOperator });

    const dialog = await openRetry(user);
    await user.click(within(dialog).getByRole('button', { name: 'Повторить' }));

    expect(await screen.findByText('Отправка повторена')).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(keys).toHaveLength(1);
    expect(keys[0]).toMatch(/^[0-9a-f-]{36}$/);
    expect(await screen.findByText('В очереди')).toBeInTheDocument();
  });

  it('keeps the dialog after a lost answer and repeats with the same key', async () => {
    const user = userEvent.setup();
    serveCard(failedCard());
    serveOperators();
    const keys = serveRetry(
      () => HttpResponse.error(),
      () => problem(503, 'HTTP_503'),
      () => HttpResponse.json(failedCard({ deliveryStatus: 'PENDING', actions: [] })),
    );
    renderApp(cardPath, { me: demoOperator });

    const dialog = await openRetry(user);
    await user.click(within(dialog).getByRole('button', { name: 'Повторить' }));
    expect(await within(dialog).findByText(/повтор безопасен/)).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Повторить' }));
    await waitFor(() => expect(keys).toHaveLength(2));
    await user.click(within(dialog).getByRole('button', { name: 'Повторить' }));

    expect(await screen.findByText('Отправка повторена')).toBeInTheDocument();
    expect(new Set(keys).size).toBe(1);
  });

  it('says a retry is no longer needed when the state changed, asks the card again and closes on «Понятно»', async () => {
    const user = userEvent.setup();
    const calls = serveCard(failedCard());
    serveOperators();
    serveRetry(() => problem(409, 'INVALID_STATE'));
    renderApp(cardPath, { me: demoOperator });

    const dialog = await openRetry(user);
    const before = calls.count;
    await user.click(within(dialog).getByRole('button', { name: 'Повторить' }));

    expect(await within(dialog).findByText('Повтор уже не нужен: состояние доставки изменилось')).toBeInTheDocument();
    await waitFor(() => expect(calls.count).toBeGreaterThan(before));
    await user.click(within(dialog).getByRole('button', { name: 'Понятно' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('shows «not found» when the request left the operator’s scope', async () => {
    const user = userEvent.setup();
    let gone = false;
    serveCard(failedCard());
    server.use(
      http.get(`*/api/requests/${CARD_ID}`, () => (gone ? problem(404, 'NOT_FOUND') : HttpResponse.json(failedCard()))),
    );
    serveOperators();
    serveRetry(() => {
      gone = true;
      return problem(404, 'NOT_FOUND');
    });
    renderApp(cardPath, { me: demoOperator });

    const dialog = await openRetry(user);
    await user.click(within(dialog).getByRole('button', { name: 'Повторить' }));
    expect(await screen.findByRole('heading', { name: 'Заявка не найдена' })).toBeInTheDocument();
  });

  it('explains a refusal of rights', async () => {
    const user = userEvent.setup();
    serveCard(failedCard());
    serveOperators();
    serveRetry(() => problem(403, 'FORBIDDEN'));
    renderApp(cardPath, { me: demoOperator });

    const dialog = await openRetry(user);
    await user.click(within(dialog).getByRole('button', { name: 'Повторить' }));
    expect(await within(dialog).findByText('Действие доступно только оператору службы заявки')).toBeInTheDocument();
  });

  it('makes a new key after IDEMPOTENCY_KEY_REUSED, and for a new dialog after a success', async () => {
    const user = userEvent.setup();
    serveCard(failedCard());
    serveOperators();
    const keys = serveRetry(
      () => problem(422, 'IDEMPOTENCY_KEY_REUSED'),
      () => HttpResponse.json(failedCard()),
      () => HttpResponse.json(failedCard()),
    );
    renderApp(cardPath, { me: demoOperator });

    let dialog = await openRetry(user);
    await user.click(within(dialog).getByRole('button', { name: 'Повторить' }));
    expect(await within(dialog).findByText('Повторите действие')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Повторить' }));
    await screen.findByText('Отправка повторена');

    dialog = await openRetry(user);
    await user.click(within(dialog).getByRole('button', { name: 'Повторить' }));
    await waitFor(() => expect(keys).toHaveLength(3));
    expect(new Set(keys).size).toBe(3);
  });
});
