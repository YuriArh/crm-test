// @vitest-environment jsdom
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import type { RequestPage } from '@/shared/api';
import { demoInitiator, server } from '@/test/server';
import { renderApp } from '@/test/renderApp';
import { demoOperator, findRow, listItem, listPage, systemInfoHandler } from './fixtures';

function serveList(page: RequestPage) {
  const queries: URLSearchParams[] = [];
  server.use(
    systemInfoHandler,
    http.get('*/api/requests', ({ request }) => {
      queries.push(new URL(request.url).searchParams);
      return HttpResponse.json(page);
    }),
  );
  return queries;
}

describe('requests with problems in the registry', () => {
  it('lets operators show only requests with open problems, through the address', async () => {
    const user = userEvent.setup();
    const queries = serveList(listPage([listItem({ openProblems: 1 })]));
    const { router } = renderApp('/requests', { me: demoOperator });

    const bar = await screen.findByTestId('filter-bar');
    await user.click(within(bar).getByRole('switch', { name: 'Есть открытые проблемы' }));

    await waitFor(() => expect(router.state.location.search).toEqual({ problems: 1 }));
    await waitFor(() => expect(queries.at(-1)!.get('hasOpenProblems')).toBe('true'));
    expect(queries[0]!.get('hasOpenProblems')).toBeNull();
  });

  it('marks a row with the number of open problems, in words for screen readers', async () => {
    serveList(listPage([listItem({ number: 'DR-000777', openProblems: 2 }), listItem({ number: 'DR-000778', openProblems: 0 })]));
    renderApp('/requests', { me: demoOperator });

    const marked = await findRow('DR-000777');
    expect(within(marked).getByLabelText('Открытых проблем: 2')).toHaveTextContent('2');
    expect(within(await findRow('DR-000778')).queryByLabelText(/Открытых проблем/)).not.toBeInTheDocument();
    const cards = screen.getByTestId('request-cards');
    expect(within(cards).getByLabelText('Открытых проблем: 2')).toBeInTheDocument();
  });

  it('says so when no request has open problems', async () => {
    serveList(listPage([]));
    renderApp('/requests?problems=1', { me: demoOperator });

    expect(await screen.findByText('Заявок с открытыми проблемами нет')).toBeInTheDocument();
  });

  it('has neither the switch nor marks for initiators, and drops problems=1 from their address', async () => {
    const queries = serveList(listPage([listItem({ number: 'DR-000779' })]));
    const { router } = renderApp('/requests?problems=1', { me: demoInitiator });

    const row = await findRow('DR-000779');
    expect(within(row).queryByLabelText(/Открытых проблем/)).not.toBeInTheDocument();
    expect(screen.queryByRole('switch', { name: 'Есть открытые проблемы' })).not.toBeInTheDocument();
    await waitFor(() => expect(router.state.location.search).toEqual({}));
    expect(queries.every((query) => query.get('hasOpenProblems') === null)).toBe(true);
  });
});
