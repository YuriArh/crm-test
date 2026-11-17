import { ActionIcon, Badge, Button, Group, Indicator } from '@mantine/core';
import { IconAlertTriangle, IconList } from '@tabler/icons-react';
import { useQuery } from '@tanstack/react-query';
import { Link, useMatchRoute } from '@tanstack/react-router';
import { useCurrentUser } from '@/auth';
import { openCountQueryOptions } from '@/problems';

/**
 * The sections of the signed-in part, in the header (web-problems.md §6.1). Only operators have more than one
 * section; initiators get nothing here, and nothing asks for problems on their behalf.
 */
export function AppNav() {
  const me = useCurrentUser();
  return me.role === 'OPERATOR' ? <OperatorNav /> : null;
}

function OperatorNav() {
  const matchRoute = useMatchRoute();
  // A hint, not data: a failure leaves the badge out without any message.
  const { data: open } = useQuery(openCountQueryOptions);
  const count = open && open > 0 ? open : null;
  const problemsName = count ? `Проблемы, открытых: ${count}` : 'Проблемы';
  const inRequests = Boolean(matchRoute({ to: '/requests', fuzzy: true }));
  const inProblems = Boolean(matchRoute({ to: '/problems', fuzzy: true }));
  const current = (active: boolean) => (active ? ({ 'aria-current': 'page' } as const) : {});

  return (
    <Group component="nav" aria-label="Разделы" gap="xs" wrap="nowrap">
      {/* Below 36em (phones) the sections are icons; their names stay for screen readers. */}
      <Group gap={4} wrap="nowrap" visibleFrom="xs">
        <Button
          variant={inRequests ? 'light' : 'subtle'}
          size="xs"
          renderRoot={(props) => <Link {...props} to="/requests" />}
          {...current(inRequests)}
        >
          Заявки
        </Button>
        <Button
          variant={inProblems ? 'light' : 'subtle'}
          size="xs"
          aria-label={problemsName}
          rightSection={
            count && (
              <Badge size="xs" color="orange" circle={count < 10}>
                {count}
              </Badge>
            )
          }
          renderRoot={(props) => <Link {...props} to="/problems" />}
          {...current(inProblems)}
        >
          Проблемы
        </Button>
      </Group>
      <Group gap={4} wrap="nowrap" hiddenFrom="xs" data-testid="nav-icons">
        <ActionIcon
          variant={inRequests ? 'light' : 'subtle'}
          size="lg"
          aria-label="Заявки"
          renderRoot={(props) => <Link {...props} to="/requests" />}
          {...current(inRequests)}
        >
          <IconList size="1.1em" />
        </ActionIcon>
        <Indicator label={count} disabled={!count} color="orange" size={16} offset={4}>
          <ActionIcon
            variant={inProblems ? 'light' : 'subtle'}
            size="lg"
            aria-label={problemsName}
            renderRoot={(props) => <Link {...props} to="/problems" />}
            {...current(inProblems)}
          >
            <IconAlertTriangle size="1.1em" />
          </ActionIcon>
        </Indicator>
      </Group>
    </Group>
  );
}
