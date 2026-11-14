import { Button, Center, Stack, Text, Title } from '@mantine/core';
import { useRouter, type ErrorComponentProps } from '@tanstack/react-router';
import { NetworkError } from '@/shared/api';

/** Shown when a route fails to load or render. "Повторить" loads the current routes again. */
export function ErrorPage({ error }: ErrorComponentProps) {
  const router = useRouter();
  const message =
    error instanceof NetworkError
      ? 'Нет связи с сервером. Проверьте подключение и повторите.'
      : 'Не удалось загрузить страницу. Повторите попытку позже.';

  return (
    <Center mih="100vh" p="md">
      <Stack align="center" gap="sm">
        <Title order={2}>Что-то пошло не так</Title>
        <Text c="dimmed">{message}</Text>
        <Button onClick={() => void router.invalidate()}>Повторить</Button>
      </Stack>
    </Center>
  );
}
