import { Button, Group, Modal, Text } from '@mantine/core';
import { useBlocker } from '@tanstack/react-router';

/**
 * Asks before leaving the card with unsaved changes: navigation inside the app gets a Mantine modal, a
 * reload or closing the tab the browser's own prompt. Going to the login page is never held: the session
 * is gone or the user signs out on purpose.
 */
export function LeaveGuard({ dirty }: { dirty: boolean }) {
  const blocker = useBlocker({
    shouldBlockFn: ({ next }) => dirty && next.pathname !== '/login',
    enableBeforeUnload: () => dirty,
    withResolver: true,
  });

  return (
    <Modal
      opened={blocker.status === 'blocked'}
      onClose={() => blocker.reset?.()}
      title="Уйти без сохранения?"
      centered
    >
      <Text size="md">Несохранённые изменения комментария или ответственного пропадут.</Text>
      <Group justify="flex-end" gap="sm" mt="lg">
        <Button variant="default" onClick={() => blocker.reset?.()}>
          Остаться
        </Button>
        <Button color="red" onClick={() => blocker.proceed?.()}>
          Уйти
        </Button>
      </Group>
    </Modal>
  );
}
