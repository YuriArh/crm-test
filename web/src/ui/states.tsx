import { Alert, Button, Center, Group, Loader, Stack, Text, Title } from '@mantine/core';

import { describeError } from '../api/errors';

export function FullPageLoader() {
  return (
    <Center h="60vh" role="status" aria-label="Загрузка">
      <Loader />
    </Center>
  );
}

export function FullPageError({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  return (
    <Center h="60vh" p="md">
      <ErrorAlert error={error} onRetry={onRetry} />
    </Center>
  );
}

export function ErrorAlert({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  return (
    <Alert color="red" title="Не удалось загрузить данные" role="alert" maw={560} w="100%">
      <Stack gap="sm">
        <Text size="sm">{describeError(error)}</Text>
        {onRetry && (
          <Group>
            <Button size="xs" variant="light" color="red" onClick={onRetry}>
              Повторить
            </Button>
          </Group>
        )}
      </Stack>
    </Alert>
  );
}

export function EmptyState({
  title,
  text,
  action,
}: {
  title: string;
  text?: string;
  action?: React.ReactNode;
}) {
  return (
    <Stack align="center" gap="xs" py="xl" ta="center">
      <Title order={3} size="h4">
        {title}
      </Title>
      {text && (
        <Text c="dimmed" size="sm" maw={420}>
          {text}
        </Text>
      )}
      {action}
    </Stack>
  );
}
