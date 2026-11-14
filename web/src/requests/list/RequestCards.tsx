import { Group, Skeleton, Stack, Text } from '@mantine/core';
import { Link } from '@tanstack/react-router';
import type { RequestListItem, Role } from '@/shared/api';
import { StatusBadge } from '../StatusBadge';
import { BUSINESS_VIEWS, deliveryView } from '../statuses';
import { Deadline } from './Deadline';
import classes from './RequestList.module.css';

type Props = { items: RequestListItem[]; role: Role };

/** The registry on a phone (< md): one card per request, each card is a link to the request. */
export function RequestCards({ items, role }: Props) {
  return (
    <Stack gap="md" data-testid="request-cards">
      {items.map((item) => (
        <Link key={item.id} to="/requests/$id" params={{ id: item.id }} className={classes.card}>
          <Stack gap="xs">
            <Group justify="space-between" gap="sm" wrap="nowrap">
              <Text size="md" className={classes.number}>
                {item.number}
              </Text>
              <StatusBadge view={BUSINESS_VIEWS[item.businessStatus]} />
            </Group>
            <Text size="lg" lineClamp={2}>
              {item.cargoDescription}
            </Text>
            <Group gap="sm" wrap="wrap">
              <Deadline date={item.deadline} overdue={item.overdue} short />
              {role === 'OPERATOR' && (
                <Text size="md" c="dimmed">
                  {item.owner.displayName}
                </Text>
              )}
            </Group>
            {/* The usual state does not make noise: only a delivery that is not "received" is shown. */}
            {item.deliveryStatus !== 'ACKNOWLEDGED' && (
              <div>
                <StatusBadge view={deliveryView(item.deliveryStatus, role)} />
              </div>
            )}
          </Stack>
        </Link>
      ))}
    </Stack>
  );
}

/** First load on a phone: four grey cards. */
export function RequestCardsSkeleton() {
  return (
    <Stack gap="md">
      {Array.from({ length: 4 }, (_, index) => (
        <div key={index} className={classes.card} data-testid="skeleton-card">
          <Stack gap="sm">
            <Skeleton h="0.75rem" w="40%" radius="sm" />
            <Skeleton h="0.75rem" radius="sm" />
            <Skeleton h="0.75rem" w="60%" radius="sm" />
          </Stack>
        </div>
      ))}
    </Stack>
  );
}
