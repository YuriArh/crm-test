// @vitest-environment jsdom
import { screen, waitFor } from '@testing-library/react';
import { http } from 'msw';
import { describe, expect, it } from 'vitest';
import { meKey } from '@/auth/keys';
import { systemInfoHandler } from '@/requests/list/fixtures';
import { demoInitiator, problem, server } from '@/test/server';
import { renderApp } from '@/test/renderApp';

describe('a lost session', () => {
  it('sends the user to the login page with the way back when any call answers 401', async () => {
    server.use(
      http.get('*/api/requests', () => problem(401, 'UNAUTHENTICATED')),
      systemInfoHandler,
    );
    const { queryClient, history } = renderApp('/requests?result=REJECTED', { me: demoInitiator });

    expect(await screen.findByRole('heading', { name: 'Вход' })).toBeInTheDocument();
    await waitFor(() => expect(history.location.pathname).toBe('/login'));
    expect(new URLSearchParams(history.location.search).get('next')).toBe('/requests?result=REJECTED');
    expect(queryClient.getQueryData(meKey)).toBeNull();
  });
});
