import { Button, Center, Stack, Text, Title } from '@mantine/core';
import { Link } from '@tanstack/react-router';

/** Someone else's, a missing or a malformed request: the server does not tell them apart, nor does the UI. */
export function RequestNotFound() {
  return (
    <Center py="xl">
      <Stack align="center" gap="sm">
        <Title order={2}>Заявка не найдена</Title>
        <Text c="dimmed" ta="center">
          Такой заявки нет или у вас нет к ней доступа.
        </Text>
        <Button component={Link} to="/requests" variant="light">
          К списку заявок
        </Button>
      </Stack>
    </Center>
  );
}
