import { createRoute } from '@tanstack/react-router';
import { authenticatedRoute, requireRole } from '@/auth';
import { systemInfoQueryOptions } from '@/shared/system';
import { CreateRequestPage } from './CreateRequestPage';

/**
 * The create form. Only initiators create requests; anyone else gets "not found", as the server's scope
 * rules would answer (dispatch still answers `403`, NF02). A static path, so it wins over `/requests/$id`.
 */
export const createRequestRoute = createRoute({
  getParentRoute: () => authenticatedRoute,
  path: '/requests/new',
  beforeLoad: ({ context }) => requireRole(context.me, 'INITIATOR'),
  // The business time zone decides "today" for the deadline; fetched before the form shows, and a
  // failure leaves the default zone rather than blocking the form (the server checks the date anyway).
  loader: ({ context }) => context.queryClient.prefetchQuery(systemInfoQueryOptions),
  component: CreateRequestPage,
});
