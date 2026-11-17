import { Button, Drawer, Skeleton, Stack, Text, useMantineTheme } from '@mantine/core';
import { useMediaQuery } from '@mantine/hooks';
import { useQuery } from '@tanstack/react-query';
import { getRouteApi } from '@tanstack/react-router';
import { isApiError } from '@/shared/api';
import { problemQueryOptions } from '../api';
import { ProblemDetails } from '../details/ProblemDetails';
import { kindView } from '../labels';
import { useDecisionDialog } from '../resolve/useDecisionDialog';

const route = getRouteApi('/_authenticated/problems/$id');

/**
 * One problem over the list (web-problems.md §5.3): loaded by id, so a link works whatever page the list shows.
 * After a decision it stays open with the closed problem; closing returns to the list with its filters.
 */
export function ProblemDrawer() {
  const { id } = route.useParams();
  const navigate = route.useNavigate();
  const theme = useMantineTheme();
  const phone = useMediaQuery(`not all and (min-width: ${theme.breakpoints.sm})`, false, {
    getInitialValueInEffect: false,
  });
  const query = useQuery(problemQueryOptions(id));
  const decisions = useDecisionDialog();
  const { data: problem, error } = query;
  const unavailable = isApiError(error) && (error.status === 404 || error.status === 403);

  const close = () => void navigate({ to: '/problems', search: true });

  return (
    <Drawer
      opened
      onClose={close}
      position="right"
      size={phone ? '100%' : 'xl'}
      title={problem ? kindView(problem.kind).label : 'Проблема'}
      closeButtonProps={{ 'aria-label': 'Закрыть' }}
    >
      {problem && <ProblemDetails problem={problem} onDecide={(decision) => decisions.open(problem, decision)} />}
      {!problem && unavailable && (
        <Stack gap="md">
          <Text>Проблема не найдена или недоступна</Text>
          <div>
            <Button variant="default" onClick={close}>
              Закрыть
            </Button>
          </div>
        </Stack>
      )}
      {!problem && error && !unavailable && (
        <Stack gap="md" role="alert">
          <Text>Не удалось загрузить проблему</Text>
          <div>
            <Button variant="default" onClick={() => void query.refetch()}>
              Повторить
            </Button>
          </div>
        </Stack>
      )}
      {!problem && !error && (
        <Stack gap="sm" data-testid="problem-skeleton">
          <Skeleton h="1rem" w="60%" radius="sm" />
          <Skeleton h="1rem" radius="sm" />
          <Skeleton h="4rem" radius="sm" />
        </Stack>
      )}
      {decisions.dialog}
    </Drawer>
  );
}
