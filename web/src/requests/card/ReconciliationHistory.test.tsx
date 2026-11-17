// @vitest-environment jsdom
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { demoInitiator } from '@/test/server';
import { renderApp } from '@/test/renderApp';
import { cardPath, demoOperator, reconciliation, serveCard, serveOperators, succeededCard, withExchange } from './testData';

const newest = reconciliation({
  checkedAt: '2026-11-17T11:05:00Z',
  outcome: 'CONFLICT',
  details: 'Results differ; decided earlier: problem 0195f3a2, not opened again',
  carrierResult: {
    status: 'REJECTED',
    trackingNumber: null,
    price: null,
    reason: 'Адрес вне зоны доставки',
    sequence: 2,
    messageId: '0195f3a2-7c41-7d10-9a5e-3f1c2b4d5e99',
    occurredAt: '2026-11-17T11:04:00Z',
  },
});
const scheduled = reconciliation({
  checkedAt: '2026-11-17T10:00:00Z',
  trigger: 'SCHEDULED',
  performedBy: null,
  outcome: 'ERROR',
  carrierState: null,
  carrierResult: null,
  details: 'Carrier unavailable: HTTP 503',
});
const startup = reconciliation({ checkedAt: '2026-11-17T09:00:00Z', trigger: 'STARTUP', performedBy: null });
/** The carrier changed its number and price; ours is the default `localResult` (TRK-3F1C2B4D, 612,50 ₽). */
const differentPrice = reconciliation({
  checkedAt: '2026-11-17T11:20:00Z',
  outcome: 'CONFLICT',
  details: 'Results differ',
  carrierResult: {
    status: 'SUCCEEDED',
    trackingNumber: 'TRK-FFFFFFFF',
    price: 999,
    reason: null,
    sequence: 2,
    messageId: '0195f3a2-7c41-7d10-9a5e-3f1c2b4d5e98',
    occurredAt: '2026-11-17T11:19:00Z',
  },
});
const onlyOurs = reconciliation({
  checkedAt: '2026-11-17T11:10:00Z',
  outcome: 'CONFLICT',
  carrierState: 'ACCEPTED',
  carrierResult: null,
  details: 'We have a result, the carrier has none',
});

async function history() {
  return screen.findByRole('region', { name: 'Сверки' });
}

function renderOperatorCard(reconciliations = [newest, scheduled, startup]) {
  serveCard({ ...withExchange(succeededCard()), reconciliations });
  serveOperators();
  renderApp(cardPath, { me: demoOperator });
}

describe('history of reconciliation checks', () => {
  it('lists the checks as dispatch sent them, newest first: time, run, outcome and details in the row', async () => {
    renderOperatorCard();

    const rows = within(await history()).getAllByRole('button');
    expect(rows).toHaveLength(3);
    expect(rows[0]).toHaveTextContent('17.11, 14:05');
    expect(rows[0]).toHaveTextContent('вручную · Оператор (Север)');
    expect(rows[0]).toHaveTextContent('Расхождение');
    // Visible without opening: a red outcome with no problem opened is explained right away.
    expect(rows[0]).toHaveTextContent('decided earlier');
    expect(rows[1]).toHaveTextContent('по расписанию');
    expect(rows[1]).toHaveTextContent('Не удалось сверить');
    expect(rows[2]).toHaveTextContent('при старте');
    expect(rows[2]).toHaveTextContent('Совпадает');
  });

  it('opens a check from the keyboard: the whole details, the carrier’s answer and its result', async () => {
    const user = userEvent.setup();
    renderOperatorCard();

    const section = await history();
    const first = within(section).getAllByRole('button')[0]!;
    first.focus();
    await user.keyboard('{Enter}');

    expect(first).toHaveAttribute('aria-expanded', 'true');
    const panel = await within(section).findByRole('region', { name: /14:05/ });
    expect(within(panel).getByText('Исполнена')).toBeInTheDocument();
    expect(within(panel).getByText('Отказ')).toBeInTheDocument();
    expect(within(panel).getByText('Адрес вне зоны доставки')).toBeInTheDocument();
    expect(within(panel).getByText('17.11, 14:04')).toBeInTheDocument();
    expect(within(panel).getByText(/0195f3a2-7c41-7d10-9a5e-3f1c2b4d5e99/)).toBeInTheDocument();
    expect(within(panel).getByText(/Results differ; decided earlier/)).toBeInTheDocument();
  });

  it('shows a check without a carrier result without that block, and says the carrier did not answer', async () => {
    const user = userEvent.setup();
    renderOperatorCard();

    const section = await history();
    const row = within(section).getAllByRole('button')[1]!;
    await user.click(row);
    expect(row).toHaveAttribute('aria-expanded', 'true');

    const panel = await within(section).findByRole('region', { name: /13:00/ });
    expect(within(panel).getByText('Не ответил')).toBeInTheDocument();
    const carrier = within(panel).getByRole('region', { name: 'У перевозчика' });
    expect(within(carrier).getByText('Нет ответа')).toBeInTheDocument();
    expect(within(carrier).queryByText('Номер отправления')).not.toBeInTheDocument();
    expect(within(within(panel).getByRole('region', { name: 'У нас' })).getByText('TRK-3F1C2B4D')).toBeInTheDocument();
    expect(within(panel).queryByText('отличается')).not.toBeInTheDocument();
    expect(within(panel).getByText('Carrier unavailable: HTTP 503')).toBeInTheDocument();
  });

  it('names what differed in the row of a conflict, without opening it', async () => {
    renderOperatorCard([differentPrice, onlyOurs, newest, startup]);

    const rows = within(await history()).getAllByRole('button');
    expect(rows[0]).toHaveTextContent('Отличается: номер отправления, стоимость');
    expect(rows[1]).toHaveTextContent('Итог есть только у нас');
    expect(rows[2]).toHaveTextContent('Отличается: статус, номер отправления, стоимость, причина отказа');
    expect(rows[3]).not.toHaveTextContent('Отличается');
  });

  it('opens a conflict as both sides of that moment, the differing fields marked in words', async () => {
    const user = userEvent.setup();
    renderOperatorCard([differentPrice]);

    const section = await history();
    await user.click(within(section).getAllByRole('button')[0]!);

    const panel = await within(section).findByRole('region', { name: /14:20/ });
    const carrier = within(panel).getByRole('region', { name: 'У перевозчика' });
    const ours = within(panel).getByRole('region', { name: 'У нас' });
    expect(within(carrier).getByText('TRK-FFFFFFFF')).toBeInTheDocument();
    expect(within(ours).getByText('TRK-3F1C2B4D')).toBeInTheDocument();
    expect(within(carrier).getAllByText('отличается')).toHaveLength(2);
  });

  it('marks nothing in a check that matched', async () => {
    const user = userEvent.setup();
    renderOperatorCard([startup]);

    const section = await history();
    await user.click(within(section).getAllByRole('button')[0]!);

    const panel = await within(section).findByRole('region', { name: /12:00/ });
    expect(within(within(panel).getByRole('region', { name: 'У нас' })).getByText('TRK-3F1C2B4D')).toBeInTheDocument();
    expect(within(panel).queryByText('отличается')).not.toBeInTheDocument();
  });

  it('says there were no checks yet', async () => {
    renderOperatorCard([]);
    expect(within(await history()).getByText('Сверок ещё не было')).toBeInTheDocument();
  });

  it('is not shown to an initiator', async () => {
    serveCard(succeededCard());
    renderApp(cardPath, { me: demoInitiator });

    await screen.findByRole('heading', { name: /DR-000123/ });
    expect(screen.queryByRole('region', { name: 'Сверки' })).not.toBeInTheDocument();
  });
});
