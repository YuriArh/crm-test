import type { Me, RequestCard } from '../api/types';

export const initiator: Me = {
  id: '0195f000-0000-7000-8000-000000000101',
  username: 'north.initiator',
  displayName: 'Инициатор (Север)',
  role: 'INITIATOR',
  service: { id: '0195f000-0000-7000-8000-000000000001', code: 'NORTH', name: 'Служба Север' },
};

export const operator: Me = {
  ...initiator,
  id: '0195f000-0000-7000-8000-000000000102',
  username: 'north.operator',
  displayName: 'Оператор (Север)',
  role: 'OPERATOR',
};

export function card(overrides: Partial<RequestCard> = {}): RequestCard {
  return {
    id: '0195f3a2-7c41-7d10-9a5e-3f1c2b4d5e60',
    number: 'DR-000123',
    cargoDescription: 'Паллеты',
    weightKg: 12.5,
    deliveryAddress: 'ул. Ленина, 1',
    deadline: '2026-12-01',
    comment: null,
    owner: { id: initiator.id, displayName: initiator.displayName },
    assignee: null,
    businessStatus: 'AWAITING',
    result: null,
    deliveryStatus: 'ACKNOWLEDGED',
    operationId: '0195f3a2-7c41-7d10-9a5e-3f1c2b4d5e6f',
    overdue: false,
    createdAt: '2026-11-12T09:15:03Z',
    updatedAt: '2026-11-12T09:15:03Z',
    version: 1,
    ...overrides,
  };
}
