// @vitest-environment jsdom
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';
import { listHandlers } from '@/requests/list/fixtures';
import type { Me } from '@/shared/api';
import { demoInitiator, problem, server } from '@/test/server';
import { renderApp } from '@/test/renderApp';
import { meKey } from './keys';

const operator: Me = {
  ...demoInitiator,
  id: '0195f000-0000-7000-8000-000000000102',
  username: 'north.operator',
  displayName: 'Оператор (Север)',
  role: 'OPERATOR',
};

describe('AuthenticatedLayout', () => {
  beforeEach(() => server.use(...listHandlers()));

  it('shows who is signed in: name, role and service', async () => {
    renderApp('/', { me: operator });

    expect(await screen.findByText('Оператор (Север)')).toBeInTheDocument();
    expect(screen.getByText('Оператор · Служба Север')).toBeInTheDocument();
  });

  it('signs out to the login page and forgets the user', async () => {
    let signedOut = false;
    server.use(
      http.post('*/api/auth/logout', () => {
        signedOut = true;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const { history, queryClient } = renderApp('/', { me: demoInitiator });

    await userEvent.click(await screen.findByRole('button', { name: 'Выйти' }));

    expect(await screen.findByRole('heading', { name: 'Вход' })).toBeInTheDocument();
    expect(history.location.pathname).toBe('/login');
    expect(queryClient.getQueryData(meKey)).toBeNull();
    expect(signedOut).toBe(true);
  });

  it('ends the local session even when the server call fails', async () => {
    server.use(http.post('*/api/auth/logout', () => problem(500, 'INTERNAL_ERROR')));
    const { history } = renderApp('/', { me: demoInitiator });

    await userEvent.click(await screen.findByRole('button', { name: 'Выйти' }));

    await waitFor(() => expect(history.location.pathname).toBe('/login'));
  });
});
