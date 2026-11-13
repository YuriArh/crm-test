import { Button } from '@mantine/core';
import { Link } from 'react-router';

import { EmptyState } from '../ui/states';

export function NotFoundPage() {
  return (
    <EmptyState
      title="Страница не найдена"
      action={
        <Button component={Link} to="/requests" variant="light">
          К списку заявок
        </Button>
      }
    />
  );
}

export function ForbiddenPage() {
  return (
    <EmptyState
      title="Недостаточно прав"
      text="Это действие недоступно для вашей роли."
      action={
        <Button component={Link} to="/requests" variant="light">
          К списку заявок
        </Button>
      }
    />
  );
}
