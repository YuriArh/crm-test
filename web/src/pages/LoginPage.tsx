import { Alert, Button, Center, Paper, PasswordInput, Stack, Text, TextInput, Title } from '@mantine/core';
import { useForm } from 'react-hook-form';
import { Navigate, useNavigate, useSearchParams } from 'react-router';

import { describeError, isApiError } from '../api/errors';
import { safeNext } from '../auth/safeNext';
import { useLogin, useMe } from '../auth/useAuth';

interface LoginForm {
  username: string;
  password: string;
}

/** Demo accounts of the test stand (NF02); the password is the same for all. */
const demoUsers = [
  { username: 'north.initiator', label: 'Инициатор, служба Север' },
  { username: 'south.initiator', label: 'Инициатор, служба Юг' },
  { username: 'north.operator', label: 'Оператор, служба Север' },
];
const DEMO_PASSWORD = 'demo1234';

export function LoginPage() {
  const [search] = useSearchParams();
  const navigate = useNavigate();
  const me = useMe();
  const login = useLogin();
  const next = safeNext(search.get('next'));
  const {
    register,
    handleSubmit,
    setValue,
    formState: { errors },
  } = useForm<LoginForm>({ defaultValues: { username: '', password: '' } });

  if (me.data) {
    return <Navigate to={next} replace />;
  }

  const onSubmit = handleSubmit((values) =>
    login.mutate(values, { onSuccess: () => void navigate(next, { replace: true }) }),
  );

  return (
    <Center mih="100vh" p="md">
      <Paper withBorder shadow="sm" p="xl" radius="md" w="100%" maw={400}>
        <form onSubmit={onSubmit} noValidate>
          <Stack>
            <Title order={1} size="h3">
              Вход в диспетчерскую
            </Title>
            {login.isError && (
              <Alert color="red" role="alert">
                {isApiError(login.error, 'BAD_CREDENTIALS')
                  ? 'Неверный логин или пароль.'
                  : describeError(login.error)}
              </Alert>
            )}
            <TextInput
              label="Логин"
              autoComplete="username"
              autoFocus
              error={errors.username?.message}
              {...register('username', { required: 'Введите логин' })}
            />
            <PasswordInput
              label="Пароль"
              autoComplete="current-password"
              error={errors.password?.message}
              {...register('password', { required: 'Введите пароль' })}
            />
            <Button type="submit" loading={login.isPending} fullWidth>
              Войти
            </Button>
            <Stack gap={4}>
              <Text size="xs" c="dimmed">
                Демо-пользователи (пароль {DEMO_PASSWORD}):
              </Text>
              {demoUsers.map((user) => (
                <Button
                  key={user.username}
                  variant="subtle"
                  size="compact-sm"
                  justify="start"
                  onClick={() => {
                    setValue('username', user.username);
                    setValue('password', DEMO_PASSWORD);
                  }}
                >
                  {user.username} — {user.label}
                </Button>
              ))}
            </Stack>
          </Stack>
        </form>
      </Paper>
    </Center>
  );
}
