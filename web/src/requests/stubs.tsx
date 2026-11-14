// TEMPORARY: lets typed links to screens built on other branches compile; the main agent removes it at merge.
import { createRoute } from '@tanstack/react-router';
import { authenticatedRoute } from '@/auth';

export const listStubRoute = createRoute({
  getParentRoute: () => authenticatedRoute,
  path: '/requests',
  component: () => null,
});

export const cardStubRoute = createRoute({
  getParentRoute: () => authenticatedRoute,
  path: '/requests/$id',
  component: () => null,
});
