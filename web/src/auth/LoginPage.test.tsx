// @vitest-environment jsdom
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { delay, http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { demoInitiator, problem, server } from '@/test/server';
import { renderApp } from '@/test/renderApp';
import { listHandlers } from '@/requests/list/fixtures';

async function fillAndSubmit(username: string, password: string) {
  const user = userEvent.setup();
  const usernameInput = await screen.findByLabelText('Логин');
  if (username) await user.type(usernameInput, username);
  if (password) await user.type(screen.getByLabelText('Пароль'), password);
  await user.click(screen.getByRole('button', { name: 'Войти' }));
  return user;
}

describe('LoginPage', () => {
  it('signs in and goes to next', async () => {
    let body: unknown;
    server.use(
      http.post('*/api/auth/login', async ({ request }) => {
        body = await request.json();
        return HttpResponse.json(demoInitiator);
      }),
      ...listHandlers(),
    );
    const { history } = renderApp('/login?next=%2Frequests%3Fresult%3DREJECTED', { me: null });

    await fillAndSubmit('  north.initiator ', 'demo1234');

    expect(await screen.findByRole('heading', { name: 'Заявки' })).toBeInTheDocument();
    expect(history.location.pathname + history.location.search).toBe('/requests?result=REJECTED');
    expect(body).toEqual({ username: 'north.initiator', password: 'demo1234' });
  });

  it('says that the login or password is wrong and clears the password', async () => {
    server.use(http.post('*/api/auth/login', () => problem(401, 'BAD_CREDENTIALS')));
    renderApp('/login', { me: null });

    await fillAndSubmit('north.initiator', 'wrong');

    expect(await screen.findByRole('alert')).toHaveTextContent('Неверный логин или пароль.');
    expect(screen.getByLabelText('Пароль')).toHaveValue('');
    expect(screen.getByLabelText('Логин')).toHaveValue('north.initiator');
  });

  it('does not send empty fields', async () => {
    let sent = false;
    server.use(
      http.post('*/api/auth/login', () => {
        sent = true;
        return HttpResponse.json(demoInitiator);
      }),
    );
    renderApp('/login', { me: null });

    await fillAndSubmit('', '');

    expect(await screen.findByText('Введите логин')).toBeInTheDocument();
    expect(screen.getByText('Введите пароль')).toBeInTheDocument();
    expect(sent).toBe(false);
  });

  it('shows server field errors under the fields', async () => {
    server.use(
      http.post('*/api/auth/login', () =>
        problem(400, 'VALIDATION_FAILED', { errors: [{ field: 'password', message: 'must not be blank' }] }),
      ),
    );
    renderApp('/login', { me: null });

    await fillAndSubmit('north.initiator', ' ');

    expect(await screen.findByText('Введите пароль')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('keeps the form when the server cannot be reached', async () => {
    server.use(http.post('*/api/auth/login', () => HttpResponse.error()));
    renderApp('/login', { me: null });

    await fillAndSubmit('north.initiator', 'demo1234');

    expect(await screen.findByRole('alert')).toHaveTextContent('Нет связи с сервером');
    expect(screen.getByLabelText('Пароль')).toHaveValue('demo1234');
  });

  it('reports a server failure', async () => {
    server.use(http.post('*/api/auth/login', () => problem(500, 'INTERNAL_ERROR')));
    renderApp('/login', { me: null });

    await fillAndSubmit('north.initiator', 'demo1234');

    expect(await screen.findByRole('alert')).toHaveTextContent('Ошибка на сервере');
  });

  it('fills the form with a demo user and clears the previous error', async () => {
    server.use(http.post('*/api/auth/login', () => problem(401, 'BAD_CREDENTIALS')));
    renderApp('/login', { me: null });
    const user = await fillAndSubmit('someone', 'wrong');
    await screen.findByRole('alert');

    await user.click(screen.getByRole('button', { name: /south\.initiator/ }));

    expect(screen.getByLabelText('Логин')).toHaveValue('south.initiator');
    expect(screen.getByLabelText('Пароль')).toHaveValue('demo1234');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('sends one request however often the button is clicked while signing in', async () => {
    let calls = 0;
    server.use(
      http.post('*/api/auth/login', async () => {
        calls += 1;
        await delay(200);
        return HttpResponse.json(demoInitiator);
      }),
      ...listHandlers(),
    );
    renderApp('/login', { me: null });

    const user = await fillAndSubmit('north.initiator', 'demo1234');
    await user.click(screen.getByRole('button', { name: /Войти/ })).catch(() => undefined);
    await user.type(screen.getByLabelText('Пароль'), '{Enter}');

    await screen.findByRole('heading', { name: 'Заявки' });
    await waitFor(() => expect(calls).toBe(1));
  });
});
