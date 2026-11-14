import { Anchor, Badge, Breadcrumbs, Group, Stack, Text, Title } from '@mantine/core';
import { Link, useCanGoBack, useRouter } from '@tanstack/react-router';
import type { MouseEvent } from 'react';
import { useCurrentUser } from '@/auth';
import type { RequestCard } from '@/shared/api';
import { BUSINESS_VIEWS, deliveryView } from '../statuses';
import { StatusBadge } from '../StatusBadge';

/** Breadcrumbs, number and both statuses; "просрочена" when the deadline has passed without a result. */
export function CardHeader({ card }: { card: RequestCard }) {
  const me = useCurrentUser();
  const router = useRouter();
  const canGoBack = useCanGoBack();

  // Back through the history keeps the filters of the list the user came from; a direct link has none.
  const backToList = (event: MouseEvent<HTMLAnchorElement>) => {
    if (!canGoBack || event.metaKey || event.ctrlKey || event.shiftKey || event.button !== 0) return;
    event.preventDefault();
    router.history.back();
  };

  return (
    <Stack gap="sm">
      <Breadcrumbs separator="/" separatorMargin="xs">
        <Anchor component={Link} to="/requests" size="sm" c="dimmed" onClick={backToList}>
          Заявки
        </Anchor>
        <Text size="sm">{card.number}</Text>
      </Breadcrumbs>
      <Group gap="sm" wrap="wrap" align="center">
        <Title order={1} size="h2" ff="monospace" fw={590}>
          {card.number}
        </Title>
        <Group gap="xs" wrap="wrap">
          <StatusBadge view={BUSINESS_VIEWS[card.businessStatus]} />
          <StatusBadge view={deliveryView(card.deliveryStatus, me.role)} />
          {card.overdue && (
            <Badge color="red" variant="light" radius="sm" tt="none" fw={510}>
              просрочена
            </Badge>
          )}
        </Group>
      </Group>
    </Stack>
  );
}
