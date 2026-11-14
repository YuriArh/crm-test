import { createRoute } from '@tanstack/react-router';
import { authenticatedRoute } from '@/auth/routes';
import { HomePage } from './HomePage';

// Temporary landing page until the requests registry takes over "/".
export const homeRoute = createRoute({
  getParentRoute: () => authenticatedRoute,
  path: '/',
  component: HomePage,
});
