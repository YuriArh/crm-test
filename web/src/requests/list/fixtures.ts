// Test data of the requests list for component tests (MSW handlers and rows); not used by the app.
import { screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import type { Me, RequestListItem, RequestPage } from '@/shared/api';
import { demoInitiator } from '@/test/server';

export const demoOperator: Me = {
  ...demoInitiator,
  id: '0195f000-0000-7000-8000-000000000102',
  username: 'north.operator',
  displayName: 'Оператор (Север)',
  role: 'OPERATOR',
};

let sequence = 0;

/** A row of the list; every call gets its own id and number. */
export function listItem(overrides: Partial<RequestListItem> = {}): RequestListItem {
  sequence += 1;
  const n = String(sequence).padStart(6, '0');
  return {
    id: `0195f000-0000-7000-8000-${n.padStart(12, '0')}`,
    number: `DR-${n}`,
    cargoDescription: `Груз ${n}`,
    deadline: '2026-12-01',
    businessStatus: 'AWAITING',
    deliveryStatus: 'ACKNOWLEDGED',
    overdue: false,
    owner: { id: demoInitiator.id, displayName: demoInitiator.displayName },
    assignee: null,
    createdAt: '2026-11-12T09:15:00Z',
    ...overrides,
  };
}

/** A page of the list as dispatch answers it. */
export function listPage(items: RequestListItem[], { page = 0, size = 20, totalElements = items.length } = {}): RequestPage {
  return { items, page, size, totalElements, totalPages: Math.ceil(totalElements / size) };
}

/** `system/info` with the business zone. */
export const systemInfoHandler = http.get('*/api/system/info', () =>
  HttpResponse.json({ application: 'dispatch', serverTime: '2026-11-14T09:00:00Z', businessTimezone: 'Europe/Moscow' }),
);

/** The list answering `page` for any query, and system info: enough to render the registry. */
export function listHandlers(page: RequestPage = listPage([])) {
  return [http.get('*/api/requests', () => HttpResponse.json(page)), systemInfoHandler];
}

/**
 * The table row of a request. jsdom renders the phone cards as well (a browser hides them with CSS), so
 * rows are found by the number link of the table, whose name is the number alone.
 */
export async function findRow(number: string): Promise<HTMLElement> {
  return (await screen.findByRole('link', { name: number })).closest('tr')!;
}
