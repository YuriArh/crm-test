import type { BusinessStatus, DeliveryStatus, FieldChange } from '@/shared/api';
import { formatPrice } from '../../format';
import { BUSINESS_VIEWS, OPERATOR_DELIVERY_VIEWS } from '../../statuses';

/** A text longer than this is folded in the history (a long comment). */
export const LONG_TEXT = 160;

const LABELS: Record<FieldChange['field'], string> = {
  comment: 'Комментарий',
  assignee: 'Ответственный',
  businessStatus: 'Статус',
  trackingNumber: 'Номер отправления',
  price: 'Стоимость',
  rejectionReason: 'Причина отказа',
  deliveryStatus: 'Доставка',
};

const NONE = '—';

/** One changed field as words: its name and both values as the card shows them. */
export type ChangeView = { label: string; from: string; to: string; long: boolean };

export function changeView(change: FieldChange): ChangeView {
  const label = (LABELS as Record<string, string>)[change.field] ?? change.field;
  if (change.field === 'assignee') {
    return {
      label,
      from: change.from?.displayName ?? 'Не назначен',
      to: change.to?.displayName ?? 'Не назначен',
      long: false,
    };
  }
  const from = value(change.field, change.from);
  const to = value(change.field, change.to);
  return { label, from, to, long: Math.max(from.length, to.length) > LONG_TEXT };
}

function value(field: string, raw: string | null): string {
  if (raw === null) return NONE;
  switch (field) {
    case 'price':
      return formatPrice(Number(raw));
    case 'businessStatus':
      return BUSINESS_VIEWS[raw as BusinessStatus]?.label ?? raw;
    case 'deliveryStatus':
      return OPERATOR_DELIVERY_VIEWS[raw as DeliveryStatus]?.label ?? raw;
    default:
      return raw;
  }
}
