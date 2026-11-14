import {
  Alert,
  Anchor,
  Button,
  Group,
  SimpleGrid,
  Stack,
  Text,
  Textarea,
  TextInput,
  Title,
  useMantineTheme,
} from '@mantine/core';
import { DatePickerInput } from '@mantine/dates';
import { useForm } from '@mantine/form';
import { useMediaQuery } from '@mantine/hooks';
import { notifications } from '@mantine/notifications';
import { IconArrowLeft, IconCalendar, IconInfoCircle } from '@tabler/icons-react';
import { Link, useNavigate } from '@tanstack/react-router';
import { useState } from 'react';
import { isApiError, isTransient, type CreateRequest } from '@/shared/api';
import { todayIn, useBusinessTimezone } from '@/shared/system';
import { useCreateRequest } from './api';
import { CancelCreateModal } from './CancelCreateModal';
import { ConfirmCreateModal } from './ConfirmCreateModal';
import classes from './CreateRequestPage.module.css';
import {
  ADDRESS_MAX,
  CARGO_MAX,
  EMPTY_FORM,
  isFilled,
  serverFieldErrors,
  toCreateRequest,
  validateCreate,
  type CreateFormValues,
} from './createSchema';
import { useIdempotencyKeys } from './idempotency';

/**
 * A label with the character counter on its right, `Груз … 12/200` (red once over the limit). The
 * counter is hidden from assistive technology so the field's name stays just the label.
 */
function labelWithCounter(label: string, value: string, max: number) {
  const length = value.trim().length;
  return {
    label: (
      <>
        <span>{label}</span>
        <Text component="span" size="xs" fw={400} c={length > max ? 'red' : 'dimmed'} aria-hidden>
          {length}/{max}
        </Text>
      </>
    ),
    labelProps: { className: classes.labelWithCounter },
  };
}

export function CreateRequestPage() {
  const timeZone = useBusinessTimezone();
  const today = todayIn(timeZone);

  const form = useForm<CreateFormValues>({
    mode: 'controlled',
    initialValues: EMPTY_FORM,
    // "Today" is read when the form is checked, so a form left open past midnight uses the new day.
    validate: (values) => validateCreate(values, todayIn(timeZone)),
  });

  const navigate = useNavigate();
  const keys = useIdempotencyKeys();
  const create = useCreateRequest();
  const [confirming, setConfirming] = useState(false);
  const [body, setBody] = useState<CreateRequest | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const [cancelling, setCancelling] = useState(false);
  const theme = useMantineTheme();
  const isPhone = useMediaQuery(`(max-width: ${theme.breakpoints.xs})`) ?? false;

  // A valid form opens «Проверьте данные» with the body exactly as it will be sent.
  const submit = form.onSubmit((valid) => {
    setFormError(null);
    setBody(toCreateRequest(valid));
    setConfirming(true);
  });

  const confirm = () => {
    if (!body || create.isPending) return;
    create.mutate(
      { body, idempotencyKey: keys.keyFor(body) },
      {
        onSuccess: (card) => {
          notifications.show({ color: 'green', message: `Заявка ${card.number} создана` });
          void navigate({ to: '/requests/$id', params: { id: card.id } });
        },
        onError: (error) => {
          setConfirming(false);
          if (isApiError(error, 'IDEMPOTENCY_KEY_REUSED')) keys.renew(body);
          if (isApiError(error, 'VALIDATION_FAILED')) {
            const fieldErrors = serverFieldErrors(error.fieldErrors);
            form.setErrors(fieldErrors);
            if (Object.keys(fieldErrors).length > 0) return;
          }
          setFormError(describeCreateError(error));
        },
      },
    );
  };

  // Leaving a filled form asks first; an empty one just goes back to the registry.
  const leaveUnlessFilled = (event: React.MouseEvent) => {
    if (isFilled(form.getValues())) {
      event.preventDefault();
      setCancelling(true);
    }
  };

  const { values } = form;

  return (
    <Stack maw="40rem" mx="auto" gap="lg">
      <Stack gap="xs">
        <Anchor component={Link} to="/requests" size="sm" c="dimmed" onClick={leaveUnlessFilled}>
          <Group gap={4} component="span">
            <IconArrowLeft size={14} aria-hidden />
            Заявки
          </Group>
        </Anchor>
        <Title order={1} size="h2">
          Новая заявка
        </Title>
      </Stack>

      <form onSubmit={submit} noValidate>
        <Stack gap="md">
          <TextInput
            {...labelWithCounter('Груз', values.cargoDescription, CARGO_MAX)}
            placeholder="Например, паллеты с плиткой"
            data-autofocus
            {...form.getInputProps('cargoDescription')}
          />
          <SimpleGrid cols={{ base: 1, xs: 2 }} spacing="md" verticalSpacing="md">
            {/*
              A text field, not NumberInput: NumberInput with a comma separator drops a dot that arrives
              by paste or autofill ("120.5" → 1205). parseWeight takes a comma or a dot; inputMode opens
              the numeric keyboard on phones.
            */}
            <TextInput
              label="Вес, кг"
              placeholder="0,0"
              inputMode="decimal"
              autoComplete="off"
              rightSection={
                <Text size="sm" c="dimmed">
                  кг
                </Text>
              }
              rightSectionPointerEvents="none"
              {...form.getInputProps('weightKg')}
            />
            <DatePickerInput
              label="Срок доставки"
              placeholder="дд.мм.гггг"
              valueFormat="DD.MM.YYYY"
              minDate={today}
              defaultDate={today}
              // On phones the calendar opens in a modal window instead of a dropdown under the field.
              dropdownType={isPhone ? 'modal' : 'popover'}
              leftSection={<IconCalendar size={16} aria-hidden />}
              leftSectionPointerEvents="none"
              {...form.getInputProps('deadline')}
            />
          </SimpleGrid>
          <Textarea
            {...labelWithCounter('Адрес доставки', values.deliveryAddress, ADDRESS_MAX)}
            placeholder="Город, улица, дом, склад"
            autosize
            minRows={2}
            maxRows={4}
            {...form.getInputProps('deliveryAddress')}
          />

          <Group gap="xs" wrap="nowrap" align="flex-start">
            <IconInfoCircle size={16} aria-hidden color="var(--mantine-color-dimmed)" />
            <Text size="sm" c="dimmed">
              После создания заявка сразу уходит перевозчику, изменить её нельзя.
            </Text>
          </Group>

          {formError && (
            <Alert color="red" role="alert">
              {formError}
            </Alert>
          )}

          {/* On phones this bar sticks to the bottom of the screen (CreateRequestPage.module.css). */}
          <Group justify="flex-end" gap="sm" className={classes.actions}>
            <Button variant="default" component={Link} to="/requests" onClick={leaveUnlessFilled}>
              Отмена
            </Button>
            <Button type="submit">Создать заявку</Button>
          </Group>
        </Stack>
      </form>

      <ConfirmCreateModal
        opened={confirming}
        body={body}
        sending={create.isPending}
        onEdit={() => setConfirming(false)}
        onConfirm={confirm}
      />
      <CancelCreateModal
        opened={cancelling}
        onStay={() => setCancelling(false)}
        onLeave={() => {
          setCancelling(false);
          void navigate({ to: '/requests' });
        }}
      />
    </Stack>
  );
}

/**
 * The message above the buttons; field errors go under the fields. `401` is handled by the app (sign in
 * again), so it gets no message here.
 */
function describeCreateError(error: Error): string | null {
  if (isTransient(error)) {
    // The outcome is unknown; a repeat sends the same Idempotency-Key, so it never makes a second request.
    return 'Не удалось создать заявку. Проверьте связь и повторите.';
  }
  if (isApiError(error, 'UNAUTHENTICATED')) return null;
  if (isApiError(error, 'IDEMPOTENCY_KEY_REUSED')) return 'Повторите отправку.';
  if (isApiError(error) && error.status === 403) return 'Создавать заявки может только инициатор.';
  if (isApiError(error, 'VALIDATION_FAILED')) return 'Проверьте поля формы.';
  return 'Не удалось создать заявку. Повторите попытку.';
}
