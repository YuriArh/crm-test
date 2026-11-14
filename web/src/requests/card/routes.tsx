import { createRoute, notFound } from '@tanstack/react-router';
import { authenticatedRoute } from '@/auth';
import { isCardGone, requestCardQueryOptions } from './api';
import { RequestCardPage } from './RequestCardPage';
import { RequestNotFound } from './RequestNotFound';

/**
 * `/requests/$id`. Someone else's, a missing and a malformed id all answer `404` (NF02) and look the same:
 * "Заявка не найдена".
 */
export const requestCardRoute = createRoute({
  getParentRoute: () => authenticatedRoute,
  path: '/requests/$id',
  loader: async ({ context, params }) => {
    try {
      await context.queryClient.ensureQueryData(requestCardQueryOptions(params.id));
    } catch (error) {
      if (isCardGone(error)) {
        throw notFound();
      }
      throw error;
    }
  },
  notFoundComponent: RequestNotFound,
  component: CardRoute,
});

function CardRoute() {
  const { id } = requestCardRoute.useParams();
  // A new id is a new page: no draft or conflict of the previous request survives.
  return <RequestCardPage key={id} id={id} />;
}
