import { Alert, Button, PasswordInput, Stack, Text, TextInput, Title } from '@mantine/core';
import { useForm } from '@mantine/form';
import { getRouteApi, useRouter } from '@tanstack/react-router';
import { ApiError, NetworkError } from '@/shared/api';
import { BrandMark } from '@/shared/ui/BrandMark';
import { ColorSchemeToggle } from '@/shared/ui/ColorSchemeToggle';
import { useLogin } from './api';
import classes from './LoginPage.module.css';
import { safeNext } from './safeNext';

const loginRouteApi = getRouteApi('/login');

/** Demo accounts of the stand (V5__demo_users.sql); one shared password. */
const DEMO_USERS = [
  { username: 'north.initiator', role: 'Инициатор · Север' },
  { username: 'south.initiator', role: 'Инициатор · Юг' },
  { username: 'north.operator', role: 'Оператор · Север' },
] as const;
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

  const fillDemo = (username: string) => {
    form.setValues({ username, password: DEMO_PASSWORD });
    form.clearErrors();
    login.reset();
  };

  const message = login.isError ? describeLoginError(login.error) : undefined;

  return (
    <main className={classes.page}>
      <div className={classes.corner}>
        <ColorSchemeToggle />
      </div>

      <Stack className={classes.column} gap="xl">
        <Stack gap="md" align="center" ta="center">
          <BrandMark size={40} />
          <Stack gap={6}>
            <Title order={1} size="h2">
              Вход
            </Title>
            <Text size="lg" className={classes.subtitle}>
              Dispatch — заявки на доставку и обмен с перевозчиком
            </Text>
          </Stack>
        </Stack>

        <form onSubmit={submit} noValidate>
          <Stack gap="lg">
            {message && (
              <Alert color="red" role="alert">
                {message}
              </Alert>
            )}
            <TextInput
              label="Логин"
              size="md"
              autoComplete="username"
              placeholder="north.initiator"
              data-autofocus
              {...form.getInputProps('username')}
            />
            <PasswordInput
              label="Пароль"
              size="md"
              autoComplete="current-password"
              {...form.getInputProps('password')}
            />
            <Button type="submit" size="md" fullWidth loading={login.isPending}>
              Войти
            </Button>
          </Stack>
        </form>

        <Stack gap="sm" className={classes.demo}>
          <Text size="sm" c="dimmed">
            Демо-пользователи, пароль у всех {DEMO_PASSWORD}:
          </Text>
          {DEMO_USERS.map((user) => (
            <Button
              key={user.username}
              variant="default"
              fullWidth
              classNames={{ inner: classes.demoUser }}
              rightSection={<span className={classes.demoRole}>{user.role}</span>}
              onClick={() => fillDemo(user.username)}
            >
              {user.username}
            </Button>
          ))}
        </Stack>
      </Stack>
    </main>
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
