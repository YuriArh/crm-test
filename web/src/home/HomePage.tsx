import { Stack, Text, Title } from '@mantine/core';

export function HomePage() {
  return (
    <Stack p="md" gap="xs">
      <Title order={2}>Dispatch</Title>
      <Text c="dimmed">Здесь появится реестр заявок.</Text>
    </Stack>
  );
}
