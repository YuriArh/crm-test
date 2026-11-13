import { AppShell, Badge, Button, Container, Group, Text, UnstyledButton } from '@mantine/core';
import { Link, Outlet, useNavigate } from 'react-router';

import { useCurrentUser, useLogout } from '../auth/useAuth';
import { useBusinessTime } from '../system/useBusinessTime';

const roleLabels = { INITIATOR: 'Инициатор', OPERATOR: 'Оператор' } as const;

export function AppLayout() {
  const me = useCurrentUser();
  const logout = useLogout();
  const navigate = useNavigate();
  const { timeZone, ready } = useBusinessTime();

  return (
    <AppShell header={{ height: 56 }} padding={0}>
      <AppShell.Header>
        <Container size="xl" h="100%">
          <Group h="100%" justify="space-between" wrap="nowrap">
            <UnstyledButton component={Link} to="/requests" fw={700}>
              Диспетчерская
            </UnstyledButton>
            <Group gap="sm" wrap="nowrap">
              <Text size="sm" fw={500} truncate maw={{ base: 120, sm: 260 }}>
                {me.displayName}
              </Text>
              <Badge variant="outline" visibleFrom="xs">
                {roleLabels[me.role]}
              </Badge>
              <Text size="sm" c="dimmed" visibleFrom="md">
                {me.service.name}
              </Text>
              <Button
                variant="subtle"
                size="xs"
                loading={logout.isPending}
                onClick={() => logout.mutate(undefined, { onSettled: () => void navigate('/login') })}
              >
                Выйти
              </Button>
            </Group>
          </Group>
        </Container>
      </AppShell.Header>
      <AppShell.Main>
        {/* Same container as the header, so the content lines up with it at every width. */}
        <Container size="xl" py="md">
          <Outlet />
          {ready && (
            <Text size="xs" c="dimmed" mt="xl">
              Даты и время — в часовом поясе {timeZone}.
            </Text>
          )}
        </Container>
      </AppShell.Main>
    </AppShell>
  );
}
