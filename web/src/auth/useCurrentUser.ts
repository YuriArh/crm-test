import { getRouteApi } from '@tanstack/react-router';
import type { Me } from '@/shared/api';

const authenticated = getRouteApi('/_authenticated');

/**
 * The signed-in user, inside the signed-in part of the app only. It comes from the route context that
 * requireAuth filled, not from the `me` query: when the session ends the query turns `null` before
 * the router has left the page, and screens must never see that.
 */
export function useCurrentUser(): Me {
  return authenticated.useRouteContext({ select: (context) => context.me });
}
