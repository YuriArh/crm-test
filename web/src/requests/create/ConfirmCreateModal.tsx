import { Alert, Button, Grid, Group, Modal, Stack, Text } from '@mantine/core';
import { IconAlertTriangle } from '@tabler/icons-react';
import { formatDate, formatWeight } from '@/requests/format';
import type { CreateRequest } from '@/shared/api';

type Props = {
  opened: boolean;
  /** The body exactly as it will be sent; kept while the window closes so its content does not vanish. */
  body: CreateRequest | null;
  sending: boolean;
  onEdit: () => void;
  onConfirm: () => void;
};

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <Grid rowGap={0} columnGap="sm">
      <Grid.Col span={{ base: 12, xs: 4 }}>
        <Text size="sm" c="dimmed">
          {label}
        </Text>
      </Grid.Col>
      <Grid.Col span={{ base: 12, xs: 8 }}>
        {/* Line breaks of the address are kept; long words wrap instead of widening the window. */}
        <Text size="md" style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
          {children}
        </Text>
      </Grid.Col>
    </Grid>
  );
}

/**
 * «Проверьте данные»: the last look at the content before it goes to the carrier, where it can no longer
 * be changed. While the request is being sent the window cannot be closed and «Создать» is disabled,
 * so one press sends one request.
 */
export function ConfirmCreateModal({ opened, body, sending, onEdit, onConfirm }: Props) {
  return (
    <Modal
      opened={opened}
      onClose={() => {
        if (!sending) onEdit();
      }}
      title="Проверьте данные"
      centered
      closeOnClickOutside={!sending}
      closeOnEscape={!sending}
      withCloseButton={!sending}
    >
      {body && (
        <Stack gap="lg">
          <Stack gap="sm">
            <Row label="Груз">{body.cargoDescription}</Row>
            <Row label="Вес">{formatWeight(body.weightKg)}</Row>
            <Row label="Адрес доставки">{body.deliveryAddress}</Row>
            <Row label="Срок доставки">{formatDate(body.deadline)}</Row>
          </Stack>
          {/* A standing warning, not an event: no role="alert" (Mantine's default) to interrupt readers. */}
          <Alert color="yellow" variant="light" role="note" icon={<IconAlertTriangle size={16} aria-hidden />}>
            После создания заявка сразу уходит перевозчику, изменить её будет нельзя.
          </Alert>
          <Group justify="flex-end" gap="sm">
            <Button variant="default" onClick={onEdit} disabled={sending}>
              Изменить
            </Button>
            <Button onClick={onConfirm} loading={sending} data-autofocus>
              Создать
            </Button>
          </Group>
        </Stack>
      )}
    </Modal>
  );
}
