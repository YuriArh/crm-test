import { Button, Group, Modal, Stack, Text } from '@mantine/core';

type Props = {
  opened: boolean;
  onStay: () => void;
  onLeave: () => void;
};

/** Asked before a filled form is abandoned: nothing is saved, there are no drafts. */
export function CancelCreateModal({ opened, onStay, onLeave }: Props) {
  return (
    <Modal
      opened={opened}
      onClose={onStay}
      title={
        <Text component="span" size="lg" fw={590}>
          Отменить создание?
        </Text>
      }
      centered
    >
      <Stack gap="lg" pt="xs">
        <Text>Введённые данные пропадут.</Text>
        <Group justify="flex-end" gap="sm">
          <Button variant="default" onClick={onStay} data-autofocus>
            Продолжить заполнение
          </Button>
          <Button color="red" onClick={onLeave}>
            Отменить создание
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
