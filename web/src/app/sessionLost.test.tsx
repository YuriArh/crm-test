// @vitest-environment jsdom
import { screen, waitFor } from '@testing-library/react';
import { http } from 'msw';
import { describe, expect, it } from 'vitest';
import { meKey } from '@/auth/keys';
import { api, unwrap } from '@/shared/api';
import { demoInitiator, problem, server } from '@/test/server';
import { renderApp } from '@/test/renderApp';

describe('a lost session', () => {
  it('sends the user to the login page with the way back when any call answers 401', async () => {
    server.use(http.get('*/api/system/info', () => problem(401, 'UNAUTHENTICATED')));
    const { queryClient, history } = renderApp('/?tab=history', { me: demoInitiator });
    await screen.findByRole('heading', { name: /Здравствуйте/ });

    // Any screen's query; system info stands in for the screens that do not exist yet.
    await queryClient
      .fetchQuery({ queryKey: ['system', 'info'], queryFn: () => unwrap(api.GET('/api/system/info')) })
      .catch(() => {});

    expect(await screen.findByRole('heading', { name: 'Вход' })).toBeInTheDocument();
    await waitFor(() => expect(history.location.pathname).toBe('/login'));
    expect(new URLSearchParams(history.location.search).get('next')).toBe('/?tab=history');
    expect(queryClient.getQueryData(meKey)).toBeNull();
  });
});
