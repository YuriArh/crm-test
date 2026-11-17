// @vitest-environment jsdom
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type { ExchangeProblem } from '@/shared/api';
import { renderInRouter } from '@/test/renderInRouter';
import {
  deliveryFailed,
  deliveryUnknown,
  externalMissing,
  invalidMessage,
  OPERATION_ID,
  orphanMessage,
  OUR_RESULT,
  reconciliationConflict,
  REQUEST_REF,
  resolvedByOperator,
  resolvedBySystem,
  resultConflict,
} from '../fixtures';
import { ProblemDetails } from './ProblemDetails';

const CURRENT_SUCCEEDED: ExchangeProblem['currentResult'] = {
  businessStatus: 'SUCCEEDED',
  result: { trackingNumber: 'TRK-00000001', price: 325, rejectionReason: null, resultAt: '2026-11-17T11:00:00Z' },
};

/** A result conflict closed by «Принять версию перевозчика»: the request has the carrier's result now. */
function appliedCarrier(overrides: Partial<ExchangeProblem> = {}): ExchangeProblem {
  return resultConflict({
    status: 'RESOLVED',
    resolvedAt: '2026-11-17T11:30:00Z',
    resolvedBy: { id: '0195f000-0000-7000-8000-000000000102', displayName: 'Оператор (Север)' },
    decision: 'APPLY_CARRIER',
    comment: 'Перевозчик подтвердил новый номер',
    currentResult: {
      businessStatus: 'SUCCEEDED',
      result: { trackingNumber: 'TRK-00000002', price: 400, rejectionReason: null, resultAt: '2026-11-17T11:30:00Z' },
    },
    actions: [],
    ...overrides,
  });
}

describe('ProblemDetails', () => {
  it('shows an open problem: kind, hint, status, reason, time in the business zone, request and operation', async () => {
    renderInRouter(<ProblemDetails problem={deliveryUnknown()} />);

    expect(await screen.findByText('Неизвестно, получена ли заявка')).toBeInTheDocument();
    expect(screen.getByText(/Ответ перевозчика потерян/)).toBeInTheDocument();
    expect(screen.getByText('Открыта')).toBeInTheDocument();
    expect(screen.getByText('No response in time')).toBeInTheDocument();
    expect(screen.getByText('17.11, 14:05')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: REQUEST_REF.number })).toHaveAttribute('href', `/requests/${REQUEST_REF.id}`);
    expect(screen.getByText(OPERATION_ID)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Скопировать идентификатор операции' })).toBeInTheDocument();
  });

  it('leaves out the kind’s name where a title already says it (the panel), keeping the status and the hint', async () => {
    renderInRouter(<ProblemDetails problem={deliveryUnknown()} showKind={false} />);

    expect(await screen.findByText('Открыта')).toBeInTheDocument();
    expect(screen.getByText(/Ответ перевозчика потерян/)).toBeInTheDocument();
    expect(screen.queryByText('Неизвестно, получена ли заявка')).not.toBeInTheDocument();
  });

  it('leaves out the request when asked (the card is the request already)', async () => {
    renderInRouter(<ProblemDetails problem={deliveryFailed()} showRequest={false} />);

    expect(await screen.findByText('Отправка не удалась')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: REQUEST_REF.number })).not.toBeInTheDocument();
  });

  it('shows a record without a request with a dash and no link', async () => {
    renderInRouter(<ProblemDetails problem={orphanMessage()} />);

    const request = (await screen.findByText('Заявка')).parentElement!;
    expect(within(request).getByText('—')).toBeInTheDocument();
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
    expect(screen.queryByText('Идентификатор операции')).not.toBeInTheDocument();
  });

  it('shows what could be read from a bad carrier record and its raw text with a copy button', async () => {
    renderInRouter(<ProblemDetails problem={invalidMessage()} />);

    const message = await screen.findByRole('region', { name: 'Сообщение перевозчика' });
    expect(within(message).getByText('TRK-00000042')).toBeInTheDocument();
    expect(within(message).getByText('42')).toBeInTheDocument();
    expect(within(message).getByText(/-1,00/)).toBeInTheDocument();
    expect(within(message).queryByText('Причина отказа')).not.toBeInTheDocument();
    expect(within(message).getByText('{"feedSeq":42,"status":"SUCCEEDED","price":-1.00}')).toBeInTheDocument();
    expect(within(message).getByRole('button', { name: 'Скопировать исходный текст' })).toBeInTheDocument();
  });

  it('shows only the raw text of a record nothing could be read from', async () => {
    renderInRouter(<ProblemDetails problem={orphanMessage()} />);

    const message = await screen.findByRole('region', { name: 'Сообщение перевозчика' });
    expect(within(message).queryByText('Номер отправления')).not.toBeInTheDocument();
    expect(within(message).getByText('{"feedSeq":43,"operationId":"not-a-uuid"}')).toBeInTheDocument();
  });

  it('puts the carrier version and our result side by side and marks what differs in words', async () => {
    renderInRouter(<ProblemDetails problem={resultConflict()} />);

    const carrier = await screen.findByRole('region', { name: 'Версия перевозчика' });
    const ours = screen.getByRole('region', { name: 'Наш итог при обнаружении' });
    expect(within(carrier).getByText('TRK-00000002')).toBeInTheDocument();
    expect(within(ours).getByText('TRK-00000001')).toBeInTheDocument();
    expect(within(carrier).getAllByText('Выполнена')).toHaveLength(1);
    // Tracking number and price differ, the status does not.
    expect(within(carrier).getAllByText('отличается')).toHaveLength(2);
  });

  it('shows a reconciliation conflict as a conflict: the carrier’s answer next to our result, differences marked', async () => {
    renderInRouter(<ProblemDetails problem={reconciliationConflict()} />);

    const carrier = await screen.findByRole('region', { name: 'Версия перевозчика' });
    const ours = screen.getByRole('region', { name: 'Наш итог при обнаружении' });
    expect(within(carrier).getByText('Отказ')).toBeInTheDocument();
    expect(within(carrier).getByText('Адрес вне зоны доставки')).toBeInTheDocument();
    expect(within(ours).getByText('TRK-00000001')).toBeInTheDocument();
    expect(within(carrier).getAllByText('отличается').length).toBeGreaterThan(0);
    expect(screen.queryByRole('region', { name: 'Сообщение перевозчика' })).not.toBeInTheDocument();
  });

  it('shows a reconciliation conflict where only we have a result', async () => {
    renderInRouter(<ProblemDetails problem={reconciliationConflict({ message: null, actions: ['KEEP_OURS'] })} />);

    const carrier = await screen.findByRole('region', { name: 'Версия перевозчика' });
    expect(within(carrier).getByText('У перевозчика итога ещё нет')).toBeInTheDocument();
    expect(
      within(screen.getByRole('region', { name: 'Наш итог при обнаружении' })).getByText('TRK-00000001'),
    ).toBeInTheDocument();
  });

  it('keeps what differed after the carrier’s version was applied, and shows the result now apart', async () => {
    renderInRouter(<ProblemDetails problem={appliedCarrier()} />);

    const carrier = await screen.findByRole('region', { name: 'Версия перевозчика' });
    const ours = screen.getByRole('region', { name: 'Наш итог при обнаружении' });
    const now = screen.getByRole('region', { name: 'Итог сейчас' });
    expect(within(carrier).getByText('TRK-00000002')).toBeInTheDocument();
    expect(within(ours).getByText('TRK-00000001')).toBeInTheDocument();
    expect(within(carrier).getAllByText('отличается')).toHaveLength(2);
    expect(within(now).getByText('TRK-00000002')).toBeInTheDocument();
    expect(screen.getByText(/Закрыта · .* · Принята версия перевозчика/)).toBeInTheDocument();
  });

  it('shows the result now after ours was kept, the differences stay marked', async () => {
    renderInRouter(<ProblemDetails problem={resolvedByOperator()} />);

    const now = await screen.findByRole('region', { name: 'Итог сейчас' });
    expect(within(now).getByText('TRK-00000001')).toBeInTheDocument();
    expect(within(screen.getByRole('region', { name: 'Версия перевозчика' })).getAllByText('отличается')).toHaveLength(2);
  });

  it('shows the result now after the system closed a conflict', async () => {
    renderInRouter(
      <ProblemDetails
        problem={reconciliationConflict({ status: 'RESOLVED', resolvedAt: '2026-11-17T11:40:00Z', decision: 'SYSTEM', actions: [] })}
      />,
    );

    expect(await screen.findByRole('region', { name: 'Итог сейчас' })).toBeInTheDocument();
    expect(screen.getByText(/Закрыта · .* · Закрыта системой/)).toBeInTheDocument();
  });

  it('does not show the result now apart while an open conflict has it unchanged', async () => {
    renderInRouter(<ProblemDetails problem={resultConflict()} />);

    await screen.findByRole('region', { name: 'Версия перевозчика' });
    expect(screen.queryByRole('region', { name: 'Итог сейчас' })).not.toBeInTheDocument();
  });

  it('without a snapshot shows our result now, says so, and marks nothing as different', async () => {
    renderInRouter(<ProblemDetails problem={appliedCarrier({ ourSide: null })} />);

    const ours = await screen.findByRole('region', { name: 'Наш итог сейчас' });
    expect(within(ours).getByText('Снимок на момент проблемы не сохранён')).toBeInTheDocument();
    expect(within(ours).getByText('TRK-00000002')).toBeInTheDocument();
    expect(screen.queryByText('отличается')).not.toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Наш итог при обнаружении' })).not.toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Итог сейчас' })).not.toBeInTheDocument();
  });

  it('shows our result for a lost operation, so the operator knows whether a retry is still needed', async () => {
    renderInRouter(
      <ProblemDetails problem={externalMissing({ currentResult: CURRENT_SUCCEEDED, ourSide: OUR_RESULT, actions: ['KEEP_OURS'] })} />,
    );

    const ours = await screen.findByRole('region', { name: 'Наш итог при обнаружении' });
    expect(within(ours).getByText('Выполнена')).toBeInTheDocument();
    expect(within(ours).getByText('TRK-00000001')).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Итог сейчас' })).not.toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Версия перевозчика' })).not.toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Сообщение перевозчика' })).not.toBeInTheDocument();
  });

  it('says a lost operation had no result of ours, and shows the one that came since', async () => {
    renderInRouter(<ProblemDetails problem={externalMissing({ currentResult: CURRENT_SUCCEEDED })} />);

    const ours = await screen.findByRole('region', { name: 'Наш итог при обнаружении' });
    expect(within(ours).getByText('Итога нет')).toBeInTheDocument();
    expect(within(screen.getByRole('region', { name: 'Итог сейчас' })).getByText('TRK-00000001')).toBeInTheDocument();
  });

  it('says a lost operation has no result of ours yet', async () => {
    renderInRouter(<ProblemDetails problem={externalMissing()} />);

    const ours = await screen.findByRole('region', { name: 'Наш итог при обнаружении' });
    expect(within(ours).getByText('Итога нет')).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Итог сейчас' })).not.toBeInTheDocument();
  });

  it('shows who closed a problem, how and why', async () => {
    renderInRouter(<ProblemDetails problem={resolvedByOperator()} />);

    expect(await screen.findByText('Закрыта · 17.11, 14:30 · Оператор (Север) · Оставлен наш итог')).toBeInTheDocument();
    expect(screen.getByText('Перевозчик подтвердил прежний номер')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Оставить/ })).not.toBeInTheDocument();
  });

  it('says the system closed it, without an author', async () => {
    renderInRouter(<ProblemDetails problem={resolvedBySystem()} />);

    expect(await screen.findByText('Закрыта · 17.11, 14:10 · Закрыта системой')).toBeInTheDocument();
  });

  it('offers only the decisions dispatch sent and reports the chosen one', async () => {
    const onDecide = vi.fn();
    const user = userEvent.setup();
    renderInRouter(<ProblemDetails problem={resultConflict({ actions: ['KEEP_OURS'] })} onDecide={onDecide} />);

    await user.click(await screen.findByRole('button', { name: 'Оставить наш итог' }));
    expect(screen.queryByRole('button', { name: 'Принять версию перевозчика' })).not.toBeInTheDocument();
    expect(onDecide).toHaveBeenCalledWith('KEEP_OURS');
  });

  it('shows no decisions without a handler, and extra actions next to them', async () => {
    renderInRouter(<ProblemDetails problem={invalidMessage()} actions={<button type="button">Повторить отправку</button>} />);

    expect(await screen.findByRole('button', { name: 'Повторить отправку' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Разобрано' })).not.toBeInTheDocument();
  });
});
