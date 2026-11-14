import { Alert, Button, Center, Paper, PasswordInput, Stack, Text, TextInput, Title } from '@mantine/core';
import { useForm } from '@mantine/form';
import { getRouteApi, useRouter } from '@tanstack/react-router';
import { ApiError, NetworkError } from '@/shared/api';
import { useLogin } from './api';
import { safeNext } from './safeNext';

const loginRouteApi = getRouteApi('/login');

const DEMO_LOGINS = ['north.initiator', 'north.operator', 'south.initiator'];
const DEMO_PASSWORD = 'demo1234';

type LoginValues = { username: string; password: string };

const REQUIRED: Record<keyof LoginValues, string> = {
  username: 'Введите логин',
  password: 'Введите пароль',
};

export function LoginPage() {
  const { next } = loginRouteApi.useSearch();
  const router = useRouter();
  const login = useLogin();
  const form = useForm<LoginValues>({
    initialValues: { username: '', password: '' },
    validate: {
      username: (value) => (value.trim() ? null : REQUIRED.username),
      password: (value) => (value ? null : REQUIRED.password),
    },
  });

  // While signing in the button is disabled (loading), and a form whose submit button is disabled
  // is not submitted by Enter either, so one sign-in sends one request.
  const submit = form.onSubmit((values) => {
    login.mutate(
      { username: values.username.trim(), password: values.password },
      {
        onSuccess: () => void router.navigate({ href: safeNext(next), replace: true }),
        onError: (error) => {
          if (error instanceof ApiError && error.code === 'BAD_CREDENTIALS') {
            form.setFieldValue('password', '');
          }
          if (error instanceof ApiError && error.code === 'VALIDATION_FAILED') {
            // Server messages are English; the form shows its own text for the fields it knows.
            for (const { field } of error.fieldErrors) {
              if (field === 'username' || field === 'password') form.setFieldError(field, REQUIRED[field]);
            }
          }
        },
      },
    );
  });

  const message = login.isError ? describeLoginError(login.error) : undefined;

  return (
    <Center mih="100vh" p="md">
      <Paper withBorder shadow="sm" p="lg" radius="md" w={380} maw="100%">
        <form onSubmit={submit} noValidate>
          <Stack>
            <Title order={2}>Вход</Title>
            {message && (
              <Alert color="red" role="alert">
                {message}
              </Alert>
            )}
            <TextInput
              label="Логин"
              autoComplete="username"
              data-autofocus
              {...form.getInputProps('username')}
            />
            <PasswordInput label="Пароль" autoComplete="current-password" {...form.getInputProps('password')} />
            <Button type="submit" loading={login.isPending}>
              Войти
            </Button>
            <Text size="sm" c="dimmed">
              Демо-пользователи: {DEMO_LOGINS.join(', ')}. Пароль: {DEMO_PASSWORD}.
            </Text>
          </Stack>
        </form>
      </Paper>
    </Center>
  );
}

/** The message above the form; field errors are shown under the fields instead. */
function describeLoginError(error: Error): string | undefined {
  if (error instanceof NetworkError) {
    return 'Нет связи с сервером. Проверьте подключение и повторите.';
  }
  if (error instanceof ApiError) {
    if (error.code === 'BAD_CREDENTIALS') return 'Неверный логин или пароль.';
    if (error.code === 'VALIDATION_FAILED') return undefined;
    if (error.status >= 500) return 'Ошибка на сервере. Повторите попытку позже.';
  }
  return 'Не удалось войти. Повторите попытку.';
}
