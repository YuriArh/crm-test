import { Stack, Text, Title } from '@mantine/core';
import { useCurrentUser } from '@/auth';

export function HomePage() {
  const me = useCurrentUser();

  return (
    <Stack p="md" gap="xs">
      <Title order={2}>Здравствуйте, {me.displayName}</Title>
      <Text c="dimmed">Здесь появится реестр заявок.</Text>
    </Stack>
  );
}
