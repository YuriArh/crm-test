import { describe, expect, it } from 'vitest';
import type { DeliveryStatus } from '@/shared/api';
import { deliveryView, isFinalBusiness, isFinalDelivery } from './statuses';

const ALL_DELIVERY: DeliveryStatus[] = ['PENDING', 'SENDING', 'RETRY_WAIT', 'UNKNOWN', 'ACKNOWLEDGED', 'FAILED'];

describe('deliveryView', () => {
  it('shows operators every state of the exchange', () => {
    expect(deliveryView('UNKNOWN', 'OPERATOR').label).toBe('Неизвестно, получена ли');
    expect(deliveryView('RETRY_WAIT', 'OPERATOR').label).toBe('Повтор отправки');
  });

  it('folds the in-between states into "Отправляется" for initiators', () => {
    expect(ALL_DELIVERY.map((status) => deliveryView(status, 'INITIATOR').label)).toEqual([
      'Отправляется',
      'Отправляется',
      'Отправляется',
      'Отправляется',
      'Получена перевозчиком',
      'Ошибка отправки',
    ]);
  });
});

describe('final states', () => {
  it('knows which statuses no longer change', () => {
    expect(isFinalBusiness('SUCCEEDED')).toBe(true);
    expect(isFinalBusiness('REJECTED')).toBe(true);
    expect(isFinalBusiness('IN_PROGRESS')).toBe(false);
    expect(isFinalDelivery('FAILED')).toBe(true);
    expect(isFinalDelivery('UNKNOWN')).toBe(false);
  });
});
