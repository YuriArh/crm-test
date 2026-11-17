// @vitest-environment jsdom
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { delay, http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { problemKeys } from '@/problems';
import type { Reconciliation } from '@/shared/api';
import { demoInitiator, problem, server } from '@/test/server';
import { renderApp } from '@/test/renderApp';
import { requestKeys } from '../keys';
import {
  CARD_ID,
  cardPath,
  demoOperator,
  pendingCard,
  reconciliation,
  serveCard,
  serveOperators,
  succeededCard,
  withExchange,
} from './testData';

type User = ReturnType<typeof userEvent.setup>;

/** Answers `POST …/reconcile` with `answers` in turn (the last one repeats) and records the keys it got. */
function serveReconcile(...answers: (() => Response | Promise<Response>)[]) {
  const keys: (string | null)[] = [];
  server.use(
    http.post(`*/api/requests/${CARD_ID}/reconcile`, ({ request }) => {
      keys.push(request.headers.get('Idempotency-Key'));
      return answers[Math.min(keys.length - 1, answers.length - 1)]!();
    }),
  );
  return keys;
}

const answer = (overrides: Partial<Reconciliation> = {}) => () => HttpResponse.json(reconciliation(overrides));

async function exchangePanel() {
  return screen.findByRole('region', { name: 'Обмен с перевозчиком' });
}

async function reconcile(user: User) {
  const button = within(await exchangePanel()).getByRole('button', { name: 'Сверить сейчас' });
  await user.click(button);
  return button;
}

function renderCard() {
  serveOperators();
  return renderApp(cardPath, { me: demoOperator });
}

describe('«Сверить сейчас»', () => {
  it('is in the exchange panel when dispatch offers RECONCILE, and nowhere without it', async () => {
    serveCard(withExchange(succeededCard()));
    renderCard();
    expect(within(await exchangePanel()).getByRole('button', { name: 'Сверить сейчас' })).toBeInTheDocument();
  });

  it('is not offered without RECONCILE', async () => {
    serveCard({ ...withExchange(succeededCard()), actions: [] });
    renderCard();
    await exchangePanel();
    expect(screen.queryByRole('button', { name: 'Сверить сейчас' })).not.toBeInTheDocument();
  });

  it('is not offered to an initiator', async () => {
    serveCard(succeededCard());
    renderApp(cardPath, { me: demoInitiator });
    await screen.findByRole('heading', { name: /DR-000123/ });
    expect(screen.queryByRole('button', { name: 'Сверить сейчас' })).not.toBeInTheDocument();
  });

  it('asks without a dialog and shows the outcome under the button: word, confirmation, details, time', async () => {
    const user = userEvent.setup();
    serveCard(withExchange(succeededCard()));
    const keys = serveReconcile(
      answer({ outcome: 'EXTERNAL_AHEAD', acknowledged: true, details: 'Took the carrier result over', checkedAt: '2026-11-17T11:05:00Z' }),
    );
    renderCard();

    const button = await reconcile(user);

    const outcome = await within(await exchangePanel()).findByRole('status');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(await within(outcome).findByText('Итог получен сверкой')).toBeInTheDocument();
    expect(outcome).toHaveTextContent('получение подтверждено');
    expect(outcome).toHaveTextContent('Took the carrier result over');
    expect(outcome).toHaveTextContent('17.11, 14:05');
    expect(keys).toHaveLength(1);
    expect(keys[0]).toMatch(/^[0-9a-f-]{36}$/);
    expect(button).toHaveFocus();
  });

  it('shows a carrier that did not answer as an outcome, not as a failure', async () => {
    const user = userEvent.setup();
    serveCard(withExchange(succeededCard()));
    serveReconcile(answer({ outcome: 'ERROR', carrierState: null, carrierResult: null, details: 'Carrier unavailable: HTTP 503' }));
    renderCard();

    await reconcile(user);
    const outcome = await within(await exchangePanel()).findByRole('status');
    expect(await within(outcome).findByText('Не удалось сверить')).toBeInTheDocument();
    expect(outcome).toHaveTextContent('Carrier unavailable: HTTP 503');
    expect(within(outcome).queryByRole('alert')).not.toBeInTheDocument();
  });

  it('cannot be pressed again while the carrier is being asked', async () => {
    const user = userEvent.setup();
    serveCard(withExchange(succeededCard()));
    let release!: () => void;
    const gate = new Promise<void>((resolve) => (release = resolve));
    const keys = serveReconcile(async () => {
      await gate;
      return HttpResponse.json(reconciliation());
    });
    renderCard();

    const button = await reconcile(user);
    await waitFor(() => expect(keys).toHaveLength(1));
    expect(button).toHaveAttribute('aria-disabled', 'true');
    await user.click(button);
    release();

    expect(await within(await exchangePanel()).findByText('Совпадает')).toBeInTheDocument();
    expect(keys).toHaveLength(1);
  });

  it('marks the card, the problems, the header counter and the registry stale after a check', async () => {
    const user = userEvent.setup();
    const calls = serveCard(withExchange(pendingCard({ deliveryStatus: 'UNKNOWN' })));
    serveReconcile(answer({ acknowledged: true }));
    const counter = { count: 0 };
    server.use(
      http.get('*/api/problems', () => {
        counter.count += 1;
        return HttpResponse.json({ items: [], page: 0, size: 1, totalElements: 0, totalPages: 0 });
      }),
    );
    const { queryClient } = renderCard();
    // Lists of other screens sit in the cache unobserved: they are only marked stale.
    queryClient.setQueryData(requestKeys.list({}), { items: [] });
    queryClient.setQueryData(problemKeys.list({}), { items: [] });

    await exchangePanel();
    await waitFor(() => expect(counter.count).toBeGreaterThan(0));
    const cardBefore = calls.count;
    const counterBefore = counter.count;
    await reconcile(user);

    // The card and the header counter are on screen: they are asked again at once.
    await waitFor(() => expect(calls.count).toBeGreaterThan(cardBefore));
    await waitFor(() => expect(counter.count).toBeGreaterThan(counterBefore));
    expect(queryClient.getQueryState(requestKeys.list({}))?.isInvalidated).toBe(true);
    expect(queryClient.getQueryState(problemKeys.list({}))?.isInvalidated).toBe(true);
  });

  it('asks the carrier anew on every press: a new key each time', async () => {
    const user = userEvent.setup();
    serveCard(withExchange(succeededCard()));
    const keys = serveReconcile(answer(), answer({ outcome: 'CONFLICT' }));
    renderCard();

    await reconcile(user);
    await within(await exchangePanel()).findByText('Совпадает');
    await reconcile(user);
    await within(await exchangePanel()).findByText('Расхождение');

    expect(keys).toHaveLength(2);
    expect(new Set(keys).size).toBe(2);
  });

  it('repeats a press whose answer was lost with the same key, and says the repeat is safe', async () => {
    const user = userEvent.setup();
    serveCard(withExchange(succeededCard()));
    const keys = serveReconcile(
      () => HttpResponse.error(),
      () => problem(503, 'HTTP_503'),
      answer(),
    );
    renderCard();

    await reconcile(user);
    expect(await within(await exchangePanel()).findByText(/повтор безопасен/)).toBeInTheDocument();
    await reconcile(user);
    await waitFor(() => expect(keys).toHaveLength(2));
    await reconcile(user);

    expect(await within(await exchangePanel()).findByText('Совпадает')).toBeInTheDocument();
    expect(new Set(keys).size).toBe(1);
  });

  it('explains a refusal of rights under the button', async () => {
    const user = userEvent.setup();
    serveCard(withExchange(succeededCard()));
    serveReconcile(() => problem(403, 'FORBIDDEN'));
    renderCard();

    await reconcile(user);
    expect(
      await within(await exchangePanel()).findByText('Действие доступно только оператору службы заявки'),
    ).toBeInTheDocument();
  });

  it('makes a new key after IDEMPOTENCY_KEY_REUSED', async () => {
    const user = userEvent.setup();
    serveCard(withExchange(succeededCard()));
    const keys = serveReconcile(() => problem(422, 'IDEMPOTENCY_KEY_REUSED'), answer());
    renderCard();

    await reconcile(user);
    expect(await within(await exchangePanel()).findByText('Повторите действие')).toBeInTheDocument();
    await reconcile(user);
    await within(await exchangePanel()).findByText('Совпадает');
    expect(new Set(keys).size).toBe(2);
  });

  it('says to reload on a key the server refused as malformed', async () => {
    const user = userEvent.setup();
    serveCard(withExchange(succeededCard()));
    serveReconcile(() => problem(400, 'IDEMPOTENCY_KEY_INVALID'));
    renderCard();

    await reconcile(user);
    expect(
      await within(await exchangePanel()).findByText('Не удалось выполнить действие. Обновите страницу'),
    ).toBeInTheDocument();
  });

  it('shows «not found» when the request left the operator’s scope', async () => {
    const user = userEvent.setup();
    let gone = false;
    server.use(
      http.get('*/api/system/info', () =>
        HttpResponse.json({ application: 'dispatch', serverTime: '2026-11-14T12:00:00Z', businessTimezone: 'Europe/Moscow' }),
      ),
      http.get(`*/api/requests/${CARD_ID}`, () =>
        gone ? problem(404, 'NOT_FOUND') : HttpResponse.json(withExchange(succeededCard())),
      ),
    );
    serveReconcile(async () => {
      gone = true;
      await delay(1);
      return problem(404, 'NOT_FOUND');
    });
    renderCard();

    await reconcile(user);
    expect(await screen.findByRole('heading', { name: 'Заявка не найдена' })).toBeInTheDocument();
  });
});
