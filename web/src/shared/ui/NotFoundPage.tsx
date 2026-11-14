import { Button, Center, Stack, Text, Title } from '@mantine/core';
import { Link } from '@tanstack/react-router';

export function NotFoundPage() {
  return (
    <Center mih="100vh" p="md">
      <Stack align="center" gap="sm">
        <Title order={2}>Страница не найдена</Title>
        <Text c="dimmed">Такого адреса нет или у вас нет к нему доступа.</Text>
        <Button component={Link} to="/" variant="light">
          На главную
        </Button>
      </Stack>
    </Center>
  );
}
