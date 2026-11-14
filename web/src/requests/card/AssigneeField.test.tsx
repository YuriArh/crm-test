// @vitest-environment jsdom
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import type { RequestCard, UpdateRequest } from '@/shared/api';
import { demoInitiator, problem, server } from '@/test/server';
import { renderApp } from '@/test/renderApp';
import { CARD_ID, cardPath, demoOperator, operators, pendingCard, serveCard, serveOperators, withExchange } from './testData';

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

function assigned(card: RequestCard, assigneeId: string | null | undefined): RequestCard {
  const operator = operators.find((candidate) => candidate.id === assigneeId);
  return {
    ...card,
    assignee: operator ? { id: operator.id, displayName: operator.displayName } : null,
    version: card.version + 1,
  };
}

// The dropdown is still in its opening transition in jsdom, hence `hidden: true` for the options.
async function choose(option: string) {
  await userEvent.click(await screen.findByRole('combobox', { name: 'Ответственный' }));
  await userEvent.click(await screen.findByRole('option', { name: option, hidden: true }));
}

describe('assignee', () => {

  it('lets an operator assign himself', async () => {
    const card = withExchange(pendingCard({ version: 5 }));
    serveCard(card);
    serveOperators();
    const bodies = servePatch((body) => HttpResponse.json(withExchange(assigned(card, body.assigneeId))));
    renderApp(cardPath, { me: demoOperator });

    expect(await screen.findByRole('combobox', { name: 'Ответственный' })).toHaveValue('Не назначен');
    await choose('Оператор (Север)');
    await userEvent.click(screen.getByRole('button', { name: 'Сохранить' }));

    expect(await screen.findByText('Сохранено')).toBeInTheDocument();
    expect(bodies).toEqual([{ version: 5, assigneeId: demoOperator.id }]);
    expect(screen.getByRole('combobox', { name: 'Ответственный' })).toHaveValue('Оператор (Север)');
  });

  it('lets an operator remove the assignee', async () => {
    const card = withExchange(pendingCard({ version: 2, assignee: { id: demoOperator.id, displayName: 'Оператор (Север)' } }));
    serveCard(card);
    serveOperators();
    const bodies = servePatch((body) => HttpResponse.json(withExchange(assigned(card, body.assigneeId))));
    renderApp(cardPath, { me: demoOperator });

    expect(await screen.findByRole('combobox', { name: 'Ответственный' })).toHaveValue('Оператор (Север)');
    await choose('Не назначен');
    await userEvent.click(screen.getByRole('button', { name: 'Сохранить' }));

    await waitFor(() => expect(bodies).toEqual([{ version: 2, assigneeId: null }]));
  });

  it('gives an initiator the name only, and never asks for the operators', async () => {
    serveCard(pendingCard({ assignee: { id: demoOperator.id, displayName: 'Оператор (Север)' } }));
    const operatorCalls = serveOperators();
    renderApp(cardPath, { me: demoInitiator });

    const properties = await screen.findByRole('region', { name: 'Свойства' });
    expect(within(properties).getByText('Оператор (Север)')).toBeInTheDocument();
    expect(screen.queryByRole('combobox', { name: 'Ответственный' })).not.toBeInTheDocument();
    expect(operatorCalls.count).toBe(0);
  });

  it('says the operator is not available on 422 and asks for the list again', async () => {
    serveCard(withExchange(pendingCard()));
    const operatorCalls = serveOperators();
    servePatch(() => problem(422, 'INVALID_ASSIGNEE'));
    renderApp(cardPath, { me: demoOperator });

    await choose('Второй оператор');
    const before = operatorCalls.count;
    await userEvent.click(screen.getByRole('button', { name: 'Сохранить' }));

    expect(await screen.findByText('Этот оператор недоступен')).toBeInTheDocument();
    await waitFor(() => expect(operatorCalls.count).toBe(before + 1));
  });

  it('saves the comment and the assignee together', async () => {
    const card = withExchange(pendingCard({ version: 7 }));
    serveCard(card);
    serveOperators();
    const bodies = servePatch((body) =>
      HttpResponse.json(withExchange({ ...assigned(card, body.assigneeId), comment: body.comment ?? null })),
    );
    renderApp(cardPath, { me: demoOperator });

    await userEvent.type(await screen.findByRole('textbox', { name: 'Комментарий' }), 'Беру');
    await choose('Оператор (Север)');
    await userEvent.click(screen.getByRole('button', { name: 'Сохранить' }));

    await waitFor(() => expect(bodies).toEqual([{ version: 7, comment: 'Беру', assigneeId: demoOperator.id }]));
  });
});
