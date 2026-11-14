import { AppShell, Button, Divider, Group, Stack, Text } from '@mantine/core';
import { Outlet, useRouter } from '@tanstack/react-router';
import type { Role } from '@/shared/api';
import { BrandMark } from '@/shared/ui/BrandMark';
import { ColorSchemeToggle } from '@/shared/ui/ColorSchemeToggle';
import { useLogout } from './api';
import { useCurrentUser } from './useCurrentUser';

const ROLE_LABELS: Record<Role, string> = {
  INITIATOR: 'Инициатор',
  OPERATOR: 'Оператор',
};

/** The frame of the signed-in part: header with the user and sign-out, the page below. */
export function AuthenticatedLayout() {
  const me = useCurrentUser();
  const router = useRouter();
  const logout = useLogout();

  // useLogout has cleared the cache by the time this runs, so /login does not bounce back, and Back
  // from /login meets the guard again instead of the old page.
  const signOut = () => logout.mutate(undefined, { onSettled: () => void router.navigate({ to: '/login' }) });

  return (
    <AppShell header={{ height: 48 }} padding="lg">
      <AppShell.Header>
        <Group h="100%" px="lg" justify="space-between" wrap="nowrap">
          <Group gap="sm" wrap="nowrap">
            <BrandMark size={22} />
            <Text fw={590} size="md">
              Dispatch
            </Text>
          </Group>
          <Group gap="sm" wrap="nowrap">
            <Stack gap={0} align="flex-end">
              <Text size="sm" fw={510} lineClamp={1}>
                {me.displayName}
              </Text>
              <Text size="xs" c="dimmed" lineClamp={1}>
                {ROLE_LABELS[me.role]} · {me.service.name}
              </Text>
            </Stack>
            <Divider orientation="vertical" my="sm" />
            <ColorSchemeToggle />
            <Button variant="default" size="xs" onClick={signOut} loading={logout.isPending}>
              Выйти
            </Button>
          </Group>
        </Group>
      </AppShell.Header>
      <AppShell.Main>
        <Outlet />
      </AppShell.Main>
    </AppShell>
  );
}
