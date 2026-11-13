import { screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';

import { card, initiator, operator } from '../test/fixtures';
import { renderAt } from '../test/render';
import { baseHandlers, problem, server } from '../test/server';

describe('auth guard', () => {
  it('sends an anonymous user to the login page with a return path', async () => {
    server.use(...baseHandlers(null));
    const { router } = renderAt('/requests/abc?x=1');
    await waitFor(() => expect(router.state.location.pathname).toBe('/login'));
    expect(router.state.location.search).toBe(`?next=${encodeURIComponent('/requests/abc?x=1')}`);
  });

  it('returns to the login page when the session is lost (401 from any call)', async () => {
    server.use(
      ...baseHandlers(initiator),
      http.get('*/api/requests', () => problem(401, 'UNAUTHENTICATED')),
    );
    const { router } = renderAt('/requests');
    await waitFor(() => expect(router.state.location.pathname).toBe('/login'));
  });

  it('does not offer the create screen to an operator', async () => {
    server.use(...baseHandlers(operator));
    const { router } = renderAt('/requests/new');
    await waitFor(() => expect(router.state.location.pathname).toBe('/forbidden'));
    expect(await screen.findByText('Недостаточно прав')).toBeInTheDocument();
  });

  it('shows a request outside the scope as not found', async () => {
    server.use(
      ...baseHandlers(initiator),
      http.get('*/api/requests/:id', () => problem(404, 'NOT_FOUND')),
    );
    renderAt(`/requests/${card().id}`);
    expect(await screen.findByText('Заявка не найдена')).toBeInTheDocument();
  });

  it('shows the exchange block to operators only', async () => {
    const operatorCard = card({
      exchange: {
        attempts: 1,
        nextAttemptAt: null,
        lastError: null,
        acknowledgedAt: null,
        acknowledgedVia: null,
      },
    });
    server.use(
      ...baseHandlers(operator),
      http.get('*/api/requests/:id', () => HttpResponse.json(operatorCard)),
      http.get('*/api/users/operators', () => HttpResponse.json([])),
    );
    renderAt(`/requests/${operatorCard.id}`);
    expect(await screen.findByRole('region', { name: 'Обмен с перевозчиком' })).toBeInTheDocument();
  });
});
