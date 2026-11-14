import { Anchor, Button, Group, NumberInput, SimpleGrid, Stack, Text, Textarea, TextInput, Title } from '@mantine/core';
import { DatePickerInput } from '@mantine/dates';
import { useForm } from '@mantine/form';
import { IconArrowLeft, IconCalendar, IconInfoCircle } from '@tabler/icons-react';
import { Link } from '@tanstack/react-router';
import { todayIn, useBusinessTimezone } from '@/shared/system';
import classes from './CreateRequestPage.module.css';
import { ADDRESS_MAX, CARGO_MAX, EMPTY_FORM, validateCreate, type CreateFormValues } from './createSchema';

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

  const submit = form.onSubmit(() => {
    // The confirmation and sending come in the next step.
  });

  const { values } = form;

  return (
    <Stack maw="40rem" mx="auto" gap="lg">
      <Stack gap="xs">
        <Anchor component={Link} to="/requests" size="sm" c="dimmed">
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
            <NumberInput
              label="Вес, кг"
              placeholder="0,0"
              inputMode="decimal"
              decimalSeparator=","
              allowedDecimalSeparators={['.', ',']}
              decimalScale={1}
              allowNegative={false}
              step={0.1}
              hideControls
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

          <Group justify="flex-end" gap="sm">
            <Button variant="default" component={Link} to="/requests">
              Отмена
            </Button>
            <Button type="submit">Создать заявку</Button>
          </Group>
        </Stack>
      </form>
    </Stack>
  );
}
