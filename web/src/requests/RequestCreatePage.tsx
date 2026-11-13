import { Alert, Button, Group, NumberInput, Paper, Stack, Textarea, TextInput, Title } from '@mantine/core';
import { DatePickerInput } from '@mantine/dates';
import { notifications } from '@mantine/notifications';
import { zodResolver } from '@hookform/resolvers/zod';
import { useMemo } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { Link, useNavigate } from 'react-router';

import { describeError, isApiError, NetworkError } from '../api/errors';
import type { CreateRequest } from '../api/types';
import { useBusinessTime } from '../system/useBusinessTime';
import { useCreateRequest } from './api';
import {
  createRequestSchema,
  translateServerMessage,
  type CreateRequestForm,
  type CreateRequestValues,
} from './createSchema';
import { useIdempotencyKey } from './useIdempotencyKey';

const FIELDS = ['cargoDescription', 'weightKg', 'deliveryAddress', 'deadline'] as const;

export function RequestCreatePage() {
  const navigate = useNavigate();
  const create = useCreateRequest();
  const keyFor = useIdempotencyKey();
  const { today, ready } = useBusinessTime();
  const minDate = today();
  const schema = useMemo(() => createRequestSchema(minDate), [minDate]);

  const {
    control,
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<CreateRequestForm, unknown, CreateRequestValues>({
    resolver: zodResolver(schema),
    defaultValues: { cargoDescription: '', weightKg: undefined, deliveryAddress: '', deadline: undefined },
  });

  const onSubmit = handleSubmit((values) => {
    // "Today" is checked in the business zone; until it is known the check would use UTC.
    if (!ready) return;
    const body: CreateRequest = values;
    create.mutate(
      { body, idempotencyKey: keyFor(body) },
      {
        onSuccess: ({ card }) => {
          notifications.show({
            color: 'green',
            message: `Заявка ${card.number} создана и передаётся перевозчику`,
          });
          void navigate(`/requests/${card.id}`, { replace: true });
        },
        onError: (error) => {
          if (isApiError(error, 'VALIDATION_FAILED')) {
            for (const fieldError of error.fieldErrors) {
              const field = FIELDS.find((name) => name === fieldError.field);
              if (field) {
                setError(field, { message: translateServerMessage(fieldError.message) });
              }
            }
          }
        },
      },
    );
  });

  const serverError = create.error;
  const fieldErrorsShown = isApiError(serverError, 'VALIDATION_FAILED') && serverError.fieldErrors.length > 0;

  return (
    <Stack gap="md" maw={640}>
      <Title order={1} size="h2">
        Новая заявка
      </Title>
      <Paper withBorder p={{ base: 'md', sm: 'lg' }} radius="md">
        <form onSubmit={onSubmit} noValidate>
          <Stack>
            {serverError && !fieldErrorsShown && (
              <Alert color="red" role="alert" title="Заявка не создана">
                {serverError instanceof NetworkError
                  ? 'Нет связи с сервером. Нажмите «Создать» ещё раз: повтор не создаст вторую заявку.'
                  : describeError(serverError)}
              </Alert>
            )}
            <TextInput
              label="Груз"
              description="Что везём"
              withAsterisk
              maxLength={200}
              error={errors.cargoDescription?.message}
              {...register('cargoDescription')}
            />
            <Controller
              control={control}
              name="weightKg"
              render={({ field }) => (
                <NumberInput
                  label="Вес, кг"
                  withAsterisk
                  min={0.1}
                  max={20000}
                  step={0.1}
                  decimalScale={1}
                  decimalSeparator=","
                  allowNegative={false}
                  clampBehavior="none"
                  error={errors.weightKg?.message}
                  name={field.name}
                  ref={field.ref}
                  onBlur={field.onBlur}
                  value={field.value ?? ''}
                  onChange={(value) => field.onChange(typeof value === 'number' ? value : undefined)}
                />
              )}
            />
            <Textarea
              label="Адрес доставки"
              withAsterisk
              autosize
              minRows={2}
              maxLength={500}
              error={errors.deliveryAddress?.message}
              {...register('deliveryAddress')}
            />
            <Controller
              control={control}
              name="deadline"
              render={({ field }) => (
                <DatePickerInput
                  label="Срок доставки"
                  description="Последний допустимый день, включительно"
                  withAsterisk
                  placeholder="Выберите дату"
                  valueFormat="DD.MM.YYYY"
                  minDate={ready ? minDate : undefined}
                  error={errors.deadline?.message}
                  name={field.name}
                  ref={field.ref}
                  onBlur={field.onBlur}
                  value={field.value ?? null}
                  onChange={(value) => field.onChange(value ?? undefined)}
                />
              )}
            />
            <Group justify="flex-end">
              <Button variant="default" component={Link} to="/requests">
                Отмена
              </Button>
              <Button type="submit" loading={create.isPending} disabled={!ready}>
                Создать
              </Button>
            </Group>
          </Stack>
        </form>
      </Paper>
    </Stack>
  );
}
