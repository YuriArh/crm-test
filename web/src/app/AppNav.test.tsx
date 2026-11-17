// @vitest-environment jsdom
import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { problemPage } from '@/problems/fixtures';
import { demoOperator } from '@/requests/card/testData';
import { listHandlers } from '@/requests/list/fixtures';
import { demoInitiator, problem, server } from '@/test/server';
import { renderApp } from '@/test/renderApp';

/** Answers the header's counter (`status=OPEN&size=1`) and records its queries. */
function serveCount(answer: () => Response) {
  const queries: URLSearchParams[] = [];
  server.use(
    http.get('*/api/problems', ({ request }) => {
      queries.push(new URL(request.url).searchParams);
      return answer();
    }),
  );
  return queries;
}

const header = () => screen.findByRole('banner');

describe('navigation in the header', () => {
  beforeEach(() => server.use(...listHandlers()));

  it('offers operators the requests and the problems, with the number of open problems', async () => {
    const queries = serveCount(() => HttpResponse.json(problemPage([], { totalElements: 3 })));
    renderApp('/requests', { me: demoOperator });

    const nav = await within(await header()).findByRole('navigation', { name: 'Разделы' });
    expect(within(nav).getAllByRole('link', { name: /^Заявки/ })[0]).toHaveAttribute('href', '/requests');
    expect(within(nav).getAllByRole('link', { name: /^Проблемы/ })[0]).toHaveAttribute('href', '/problems');
    expect((await within(nav).findAllByText('3')).length).toBeGreaterThan(0);
    expect(within(nav).getAllByRole('link', { name: 'Проблемы, открытых: 3' })).not.toHaveLength(0);
    expect(queries[0]!.get('status')).toBe('OPEN');
    expect(queries[0]!.get('size')).toBe('1');
  });

  it('marks the section the user is in', async () => {
    serveCount(() => HttpResponse.json(problemPage([])));
    renderApp('/requests', { me: demoOperator });

    const nav = await within(await header()).findByRole('navigation', { name: 'Разделы' });
    expect(within(nav).getAllByRole('link', { name: /^Заявки/ })[0]).toHaveAttribute('aria-current', 'page');
    expect(within(nav).getAllByRole('link', { name: /^Проблемы/ })[0]).not.toHaveAttribute('aria-current');
  });

  it('shows no number when nothing is open', async () => {
    const queries = serveCount(() => HttpResponse.json(problemPage([])));
    renderApp('/requests', { me: demoOperator });

    const nav = await within(await header()).findByRole('navigation', { name: 'Разделы' });
    await waitFor(() => expect(queries).toHaveLength(1));
    expect(within(nav).getAllByRole('link', { name: 'Проблемы' })).not.toHaveLength(0);
    expect(within(nav).queryByText('0')).not.toBeInTheDocument();
  });

  it('shows no number and no message when the count cannot be loaded', async () => {
    const queries = serveCount(() => problem(503, 'HTTP_503'));
    renderApp('/requests', { me: demoOperator });

    const nav = await within(await header()).findByRole('navigation', { name: 'Разделы' });
    await waitFor(() => expect(queries.length).toBeGreaterThan(0));
    expect(within(nav).getAllByRole('link', { name: 'Проблемы' })).not.toHaveLength(0);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('has nothing for initiators and does not ask for problems', async () => {
    const queries = serveCount(() => HttpResponse.json(problemPage([])));
    renderApp('/requests', { me: demoInitiator });

    await screen.findByText('Инициатор (Север)');
    expect(within(await header()).queryByRole('navigation')).not.toBeInTheDocument();
    expect(queries).toHaveLength(0);
  });

  describe('on a phone', () => {
    const desktopMatchMedia = window.matchMedia;
    beforeEach(() => {
      window.matchMedia = (query: string) => ({ ...desktopMatchMedia(query), matches: query.startsWith('not all') });
    });
    afterEach(() => {
      window.matchMedia = desktopMatchMedia;
    });

    it('shows icon links named for screen readers, the number on the problems one', async () => {
      serveCount(() => HttpResponse.json(problemPage([], { totalElements: 2 })));
      renderApp('/requests', { me: demoOperator });

      const nav = await within(await header()).findByRole('navigation', { name: 'Разделы' });
      const icons = within(nav).getByTestId('nav-icons');
      // jsdom applies Mantine's hidden-from class without the media query; Playwright checks the real phone.
      expect(within(icons).getByRole('link', { name: 'Заявки', hidden: true })).toHaveAttribute('href', '/requests');
      expect(await within(icons).findByRole('link', { name: 'Проблемы, открытых: 2', hidden: true })).toHaveAttribute(
        'href',
        '/problems',
      );
    });
  });
});
