// @vitest-environment jsdom
import { act, screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { BusinessStatus } from '@/shared/api';
import { demoInitiator, server } from '@/test/server';
import { renderApp } from '@/test/renderApp';
import { LIST_REFRESH_MS } from './api';
import { findRow, listItem, listPage, systemInfoHandler } from './fixtures';

function setVisibility(state: 'visible' | 'hidden') {
  Object.defineProperty(document, 'visibilityState', { value: state, configurable: true });
  // It bubbles to window in a browser, where TanStack Query listens.
  document.dispatchEvent(new Event('visibilitychange', { bubbles: true }));
}

/** The list with one request whose status the test changes; counts the calls. */
function serveChangingList() {
  const item = listItem({ number: 'DR-002001', businessStatus: 'AWAITING' });
  const state = { status: 'AWAITING' as BusinessStatus, calls: 0 };
  server.use(
    http.get('*/api/requests', () => {
      state.calls += 1;
      return HttpResponse.json(listPage([{ ...item, businessStatus: state.status }]));
    }),
    systemInfoHandler,
  );
  return state;
}

describe('auto-refresh of the list', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
  });

  afterEach(() => {
    vi.useRealTimers();
    setVisibility('visible');
  });

  it('fetches the list again every 10 s and shows the new status in place, without a skeleton', async () => {
    const list = serveChangingList();
    renderApp('/requests', { me: demoInitiator });
    expect(within(await findRow('DR-002001')).getByText('Ожидает результата')).toBeInTheDocument();
    const callsAfterLoad = list.calls;

    list.status = 'SUCCEEDED';
    await act(() => vi.advanceTimersByTimeAsync(LIST_REFRESH_MS));

    expect(await within(await findRow('DR-002001')).findByText('Выполнена')).toBeInTheDocument();
    expect(list.calls).toBe(callsAfterLoad + 1);
    expect(screen.queryAllByTestId('skeleton-row')).toHaveLength(0);
  });

  it('does not poll while the tab is hidden, and refreshes at once when the user comes back', async () => {
    const list = serveChangingList();
    renderApp('/requests', { me: demoInitiator });
    await findRow('DR-002001');
    const callsAfterLoad = list.calls;

    setVisibility('hidden');
    await act(() => vi.advanceTimersByTimeAsync(3 * LIST_REFRESH_MS));
    expect(list.calls).toBe(callsAfterLoad);

    list.status = 'REJECTED';
    act(() => setVisibility('visible'));

    expect(await within(await findRow('DR-002001')).findByText('Отказ')).toBeInTheDocument();
    expect(list.calls).toBe(callsAfterLoad + 1);
  });

  it('keeps the rows and warns when a refresh fails', async () => {
    let fail = false;
    server.use(
      http.get('*/api/requests', () =>
        fail ? HttpResponse.error() : HttpResponse.json(listPage([listItem({ number: 'DR-002101' })])),
      ),
      systemInfoHandler,
    );
    renderApp('/requests', { me: demoInitiator });
    await findRow('DR-002101');

    fail = true;
    // The failed refresh is repeated twice (network errors are transient) before it gives up.
    await act(() => vi.advanceTimersByTimeAsync(LIST_REFRESH_MS + 10_000));

    expect(await screen.findByText('Список мог устареть')).toBeInTheDocument();
    expect(await findRow('DR-002101')).toBeInTheDocument();
  });
});
