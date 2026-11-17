import { createRoute } from '@tanstack/react-router';
import { authenticatedRoute, requireRole } from '@/auth';
import { systemInfoQueryOptions } from '@/shared/system';
import { ProblemListPage } from './list/ProblemListPage';
import { parseProblemSearch } from './list/search';
import { ProblemDrawer } from './panel/ProblemDrawer';

/**
 * `/problems` — operators only; anyone else gets "not found", as the server's scope rules would answer (dispatch
 * still answers `403`, NF02). Its state lives in the address (list/search.ts).
 */
export const problemListRoute = createRoute({
  getParentRoute: () => authenticatedRoute,
  path: '/problems',
  beforeLoad: ({ context }) => requireRole(context.me, 'OPERATOR'),
  validateSearch: parseProblemSearch,
  loader: ({ context }) => void context.queryClient.prefetchQuery(systemInfoQueryOptions),
  component: ProblemListPage,
});

/** `/problems/$id` — the problem's panel over the list; the list keeps its address. */
export const problemRoute = createRoute({
  getParentRoute: () => problemListRoute,
  path: '$id',
  component: ProblemDrawer,
});
