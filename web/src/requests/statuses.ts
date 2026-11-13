import type { BusinessStatus, DeliveryStatus, RequestCard, Role } from '../api/types';

export interface StatusView {
  label: string;
  color: string;
  /** Longer explanation for a tooltip or the card. */
  hint: string;
}

export const businessStatuses: Record<BusinessStatus, StatusView> = {
  AWAITING: { label: 'Ожидает результата', color: 'gray', hint: 'Перевозчик ещё не сообщил результат.' },
  IN_PROGRESS: { label: 'В работе', color: 'blue', hint: 'Перевозчик взял заявку в работу.' },
  SUCCEEDED: { label: 'Выполнена', color: 'green', hint: 'Получены номер отправления и стоимость.' },
  REJECTED: { label: 'Отказ', color: 'red', hint: 'Перевозчик отказал в доставке.' },
};

/** Delivery as operators see it: every technical state. */
export const deliveryStatuses: Record<DeliveryStatus, StatusView> = {
  PENDING: { label: 'В очереди', color: 'gray', hint: 'Ждёт отправки перевозчику.' },
  SENDING: { label: 'Отправляется', color: 'blue', hint: 'Отправляется перевозчику прямо сейчас.' },
  RETRY_WAIT: { label: 'Повтор отправки', color: 'yellow', hint: 'Временная ошибка, будет новая попытка.' },
  UNKNOWN: {
    label: 'Неизвестно, получена ли',
    color: 'orange',
    hint: 'Ответ перевозчика потерян: неизвестно, получил ли он заявку. Требует внимания.',
  },
  ACKNOWLEDGED: { label: 'Получена перевозчиком', color: 'teal', hint: 'Перевозчик подтвердил получение.' },
  FAILED: { label: 'Ошибка отправки', color: 'red', hint: 'Постоянная ошибка или попытки исчерпаны.' },
};

const initiatorSending: StatusView = {
  label: 'Отправляется',
  color: 'blue',
  hint: 'Заявка передаётся перевозчику.',
};

/** Delivery as initiators see it: technical details of the exchange are hidden. */
export const initiatorDeliveryStatuses: Record<DeliveryStatus, StatusView> = {
  PENDING: initiatorSending,
  SENDING: initiatorSending,
  RETRY_WAIT: initiatorSending,
  UNKNOWN: initiatorSending,
  ACKNOWLEDGED: { label: 'Получена перевозчиком', color: 'teal', hint: 'Перевозчик подтвердил получение.' },
  FAILED: {
    label: 'Ошибка отправки',
    color: 'red',
    hint: 'Заявку не удалось передать перевозчику. Оператор разберётся.',
  },
};

export function deliveryView(status: DeliveryStatus, role: Role): StatusView {
  return role === 'OPERATOR' ? deliveryStatuses[status] : initiatorDeliveryStatuses[status];
}

export function isFinalBusiness(status: BusinessStatus): boolean {
  return status === 'SUCCEEDED' || status === 'REJECTED';
}

/** The card keeps refreshing while something may still change by itself. */
export function shouldPoll(card: RequestCard | undefined): boolean {
  return card !== undefined && !isFinalBusiness(card.businessStatus) && card.deliveryStatus !== 'FAILED';
}

export const acknowledgedVia: Record<string, string> = {
  RESPONSE: 'ответ на отправку',
  RESULT: 'по результату',
  RECONCILIATION: 'по сверке',
};
