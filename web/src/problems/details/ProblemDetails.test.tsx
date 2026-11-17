// @vitest-environment jsdom
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { renderInRouter } from '@/test/renderInRouter';
import {
  deliveryFailed,
  deliveryUnknown,
  invalidMessage,
  OPERATION_ID,
  orphanMessage,
  REQUEST_REF,
  resolvedByOperator,
  resolvedBySystem,
  resultConflict,
} from '../fixtures';
import { ProblemDetails } from './ProblemDetails';

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
    const ours = screen.getByRole('region', { name: 'Наш итог' });
    expect(within(carrier).getByText('TRK-00000002')).toBeInTheDocument();
    expect(within(ours).getByText('TRK-00000001')).toBeInTheDocument();
    expect(within(carrier).getAllByText('Выполнена')).toHaveLength(1);
    // Tracking number and price differ, the status does not.
    expect(within(carrier).getAllByText('отличается')).toHaveLength(2);
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
