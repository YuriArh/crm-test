import { Alert, Button, Group, Modal, Stack, Text, Textarea } from '@mantine/core';
import { useForm } from '@mantine/form';
import { notifications } from '@mantine/notifications';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { describeActionFailure, type ActionFailure } from '@/shared/actions/errors';
import { useActionKeys } from '@/shared/actions/idempotency';
import type { ExchangeProblem, ProblemDecision, ResolveProblem } from '@/shared/api';
import { invalidateAfterDecision, useResolveProblem } from '../api';
import { ConflictView } from '../details/ConflictView';
import { problemKeys } from '../keys';
import { DECISION_ACTIONS } from '../labels';
import { COMMENT_MAX, validateComment } from './resolveSchema';

const CONSEQUENCES: Partial<Record<ProblemDecision, string>> = {
  APPLY_CARRIER: 'Итог заявки будет заменён версией перевозчика. Инициатор увидит новый итог.',
  KEEP_OURS: 'Итог заявки не изменится.',
  ACKNOWLEDGED: 'Проблема закроется, заявка не изменится.',
};

const TEXTS = {
  stale: 'Решение уже недоступно: проблему закрыли или её состояние изменилось',
  gone: 'Проблема недоступна',
};

/** What else follows from a decision on this kind (web-problems.md §8.2). */
const KIND_CONSEQUENCES: Partial<Record<string, Partial<Record<ProblemDecision, string>>>> = {
  RECONCILIATION_CONFLICT: {
    KEEP_OURS:
      'Сверка запомнит решение: этот же ответ перевозчика проблему больше не откроет, другой ответ или смена нашего итога — откроет снова.',
  },
  EXTERNAL_MISSING: { KEEP_OURS: 'Потерю операции перевозчиком вы разбираете с ним сами.' },
};

const CONFLICTS = new Set<string>(['RESULT_CONFLICT', 'RECONCILIATION_CONFLICT']);

type Intent = { problemId: string; decision: ProblemDecision; comment: string };
const fingerprint = (intent: Intent) => JSON.stringify([intent.problemId, intent.decision, intent.comment]);

type Props = { problem: ExchangeProblem; decision: ProblemDecision; onClose: () => void };

/**
 * The dialog of one decision (web-problems.md §8.2): what will change, a required comment, and the answer's errors
 * shown in place, with the comment kept. The key belongs to the decision and its comment: a repeat after a lost
 * answer sends the same key, a changed comment is another decision.
 */
export function ResolveModal({ problem, decision, onClose }: Props) {
  const queryClient = useQueryClient();
  const resolve = useResolveProblem();
  const keys = useActionKeys(fingerprint);
  const [failure, setFailure] = useState<ActionFailure | null>(null);
  const form = useForm({
    initialValues: { comment: '' },
    validate: { comment: validateComment },
  });
  const title = DECISION_ACTIONS[decision] ?? decision;
  const stale = failure?.kind === 'stale';

  const close = () => {
    if (!resolve.isPending) onClose();
  };

  const submit = form.onSubmit(({ comment }) => {
    const intent: Intent = { problemId: problem.id, decision, comment: comment.trim() };
    const body = { decision, comment: intent.comment } as ResolveProblem;
    setFailure(null);
    resolve.mutate(
      { problemId: problem.id, body, idempotencyKey: keys.keyFor(intent) },
      {
        onSuccess: () => {
          keys.forget(intent);
          notifications.show({ color: 'green', message: 'Решение сохранено' });
          onClose();
        },
        onError: (error) => {
          const described = describeActionFailure(error, TEXTS);
          if (described.kind !== 'transient') keys.forget(intent);
          if (described.kind === 'stale' || described.kind === 'gone') {
            void queryClient.invalidateQueries({ queryKey: problemKeys.one(problem.id) });
            void invalidateAfterDecision(queryClient);
          }
          if (described.kind === 'session') {
            onClose();
            return;
          }
          if (described.kind === 'invalid') {
            form.setFieldError('comment', 'Опишите основание решения');
            return;
          }
          setFailure(described);
        },
      },
    );
  });

  return (
    <Modal opened onClose={close} title={title} centered size="lg">
      <form onSubmit={submit} noValidate>
        <Stack gap="md">
          <Text size="sm">
            {[CONSEQUENCES[decision], KIND_CONSEQUENCES[problem.kind]?.[decision]].filter(Boolean).join(' ')}
          </Text>
          {CONFLICTS.has(problem.kind) && <ConflictView problem={problem} />}
          <Textarea
            label="Комментарий"
            description={`Основание решения, до ${COMMENT_MAX} символов`}
            required
            autosize
            minRows={3}
            maxLength={COMMENT_MAX + 1}
            data-autofocus
            {...form.getInputProps('comment')}
          />
          {failure && 'message' in failure && (
            <Alert color={stale ? 'yellow' : 'red'} variant="light">
              {failure.message}
            </Alert>
          )}
          <Group justify="flex-end" gap="sm">
            {stale ? (
              <Button onClick={onClose}>Понятно</Button>
            ) : (
              <>
                <Button variant="default" onClick={close} disabled={resolve.isPending}>
                  Отмена
                </Button>
                <Button type="submit" loading={resolve.isPending}>
                  Сохранить решение
                </Button>
              </>
            )}
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
