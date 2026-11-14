// What other modules may use from auth: the signed-in user and the layout to hang their routes on.
export { useCurrentUser } from './useCurrentUser';
export { authenticatedRoute } from './routes';
export { requireRole } from './guards';
