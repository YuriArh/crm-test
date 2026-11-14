import type { BusinessStatus, DeliveryStatus, Role } from '@/shared/api';

/** How a status is shown: always a word, the colour only repeats it. Colours are theme keys. */
export type StatusView = { label: string; color: string };

export const BUSINESS_VIEWS: Record<BusinessStatus, StatusView> = {
  AWAITING: { label: 'Ожидает результата', color: 'gray' },
  IN_PROGRESS: { label: 'В работе', color: 'accent' },
  SUCCEEDED: { label: 'Выполнена', color: 'green' },
  REJECTED: { label: 'Отказ', color: 'red' },
};

/** Delivery as operators see it: every technical state of the exchange. */
export const OPERATOR_DELIVERY_VIEWS: Record<DeliveryStatus, StatusView> = {
  PENDING: { label: 'В очереди', color: 'gray' },
  SENDING: { label: 'Отправляется', color: 'accent' },
  RETRY_WAIT: { label: 'Повтор отправки', color: 'yellow' },
  UNKNOWN: { label: 'Неизвестно, получена ли', color: 'orange' },
  ACKNOWLEDGED: { label: 'Получена перевозчиком', color: 'green' },
  FAILED: { label: 'Ошибка отправки', color: 'red' },
};

const SENDING: StatusView = { label: 'Отправляется', color: 'accent' };

/** Delivery as initiators see it: the four in-between states of the exchange are one "Отправляется". */
export const INITIATOR_DELIVERY_VIEWS: Record<DeliveryStatus, StatusView> = {
  PENDING: SENDING,
  SENDING,
  RETRY_WAIT: SENDING,
  UNKNOWN: SENDING,
  ACKNOWLEDGED: OPERATOR_DELIVERY_VIEWS.ACKNOWLEDGED,
  FAILED: OPERATOR_DELIVERY_VIEWS.FAILED,
};

export function deliveryView(status: DeliveryStatus, role: Role): StatusView {
  return role === 'OPERATOR' ? OPERATOR_DELIVERY_VIEWS[status] : INITIATOR_DELIVERY_VIEWS[status];
}

export function isFinalBusiness(status: BusinessStatus): boolean {
  return status === 'SUCCEEDED' || status === 'REJECTED';
}

export function isFinalDelivery(status: DeliveryStatus): boolean {
  return status === 'ACKNOWLEDGED' || status === 'FAILED';
}
