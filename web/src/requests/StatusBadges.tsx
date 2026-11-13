import { Badge, Group, Tooltip } from '@mantine/core';

import type { BusinessStatus, DeliveryStatus, Role } from '../api/types';
import { businessStatuses, deliveryView, type StatusView } from './statuses';

function StatusBadge({ caption, view }: { caption: string; view: StatusView }) {
  return (
    <Tooltip
      label={view.hint}
      multiline
      maw={260}
      withArrow
      events={{ hover: true, focus: true, touch: true }}
    >
      <Badge color={view.color} variant="light" tabIndex={0} aria-label={`${caption}: ${view.label}`}>
        {view.label}
      </Badge>
    </Tooltip>
  );
}

export function BusinessBadge({ status }: { status: BusinessStatus }) {
  return <StatusBadge caption="Результат" view={businessStatuses[status]} />;
}

export function DeliveryBadge({ status, role }: { status: DeliveryStatus; role: Role }) {
  return <StatusBadge caption="Доставка" view={deliveryView(status, role)} />;
}

export function OverdueBadge() {
  return (
    <Badge color="red" variant="filled">
      Просрочена
    </Badge>
  );
}

/** Delivery and business result are separate (D01): receipt is not execution. */
export function StatusBadges({
  business,
  delivery,
  overdue,
  role,
}: {
  business: BusinessStatus;
  delivery: DeliveryStatus;
  overdue: boolean;
  role: Role;
}) {
  return (
    <Group gap={6} wrap="wrap">
      <DeliveryBadge status={delivery} role={role} />
      <BusinessBadge status={business} />
      {overdue && <OverdueBadge />}
    </Group>
  );
}
