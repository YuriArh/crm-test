import { Alert, Button, Group, Modal, Stack, Text } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { describeActionFailure, type ActionFailure } from '@/shared/actions/errors';
import { useActionKeys } from '@/shared/actions/idempotency';
import type { DeliveryStatus, RequestCard } from '@/shared/api';
import { requestKeys } from '../keys';
import { useRetryDelivery } from './api';

const EXPLANATIONS: Partial<Record<DeliveryStatus, string>> = {
  FAILED: 'Начнётся новая серия попыток. Перевозчик получит ту же операцию и не исполнит её дважды.',
  UNKNOWN:
    'Отправим сейчас, не дожидаясь паузы. Проблема «неизвестно, получена ли» останется открытой, пока перевозчик не подтвердит получение.',
};

const TEXTS = { stale: 'Повтор уже не нужен: состояние доставки изменилось', gone: 'Заявка не найдена' };

/**
 * «Повторить отправку» (web-problems.md §8.1): shown only when dispatch put `RETRY` into the card's actions, and
 * confirmed in a dialog. One dialog is one intent: a repeat after a lost answer sends the same key, so dispatch
 * replays the retry instead of starting a second budget; a new dialog is a new retry.
 */
export function RetryAction({ card }: { card: RequestCard }) {
  const queryClient = useQueryClient();
  const retry = useRetryDelivery(card.id);
  const keys = useActionKeys((dialog: number) => String(dialog));
  const [dialog, setDialog] = useState(0);
  const [open, setOpen] = useState(false);
  const [failure, setFailure] = useState<ActionFailure | null>(null);

  if (!card.actions.includes('RETRY') && !open) return null;

  const show = () => {
    setDialog((n) => n + 1);
    setFailure(null);
    setOpen(true);
  };
  const close = () => {
    if (!retry.isPending) setOpen(false);
  };

  const confirm = () => {
    retry.mutate(keys.keyFor(dialog), {
      onSuccess: () => {
        keys.forget(dialog);
        setOpen(false);
        notifications.show({ color: 'green', message: 'Отправка повторена' });
      },
      onError: (error) => {
        const described = describeActionFailure(error, TEXTS);
        if (described.kind !== 'transient') keys.forget(dialog);
        if (described.kind === 'stale' || described.kind === 'gone') {
          void queryClient.invalidateQueries({ queryKey: requestKeys.card(card.id) });
        }
        if (described.kind === 'gone' || described.kind === 'session') {
          setOpen(false);
          return;
        }
        setFailure(described);
      },
    });
  };

  const stale = failure?.kind === 'stale';

  return (
    <>
      {card.actions.includes('RETRY') && (
        <Button variant="default" size="sm" onClick={show}>
          Повторить отправку
        </Button>
      )}
      <Modal opened={open} onClose={close} title={`Повторить отправку заявки ${card.number}?`} centered>
        <Stack gap="md">
          <Text size="sm">{EXPLANATIONS[card.deliveryStatus] ?? EXPLANATIONS.FAILED}</Text>
          {failure && 'message' in failure && (
            <Alert color={stale ? 'yellow' : 'red'} variant="light">
              {failure.message}
            </Alert>
          )}
          <Group justify="flex-end" gap="sm">
            {stale ? (
              <Button onClick={() => setOpen(false)}>Понятно</Button>
            ) : (
              <>
                <Button variant="default" onClick={close} disabled={retry.isPending}>
                  Отмена
                </Button>
                <Button onClick={confirm} loading={retry.isPending}>
                  Повторить
                </Button>
              </>
            )}
          </Group>
        </Stack>
      </Modal>
    </>
  );
}
