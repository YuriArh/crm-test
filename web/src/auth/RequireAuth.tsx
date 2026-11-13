import { Navigate, Outlet, useLocation } from 'react-router';

import { FullPageError, FullPageLoader } from '../ui/states';
import type { Role } from '../api/types';
import { CurrentUserContext } from './currentUserContext';
import { useCurrentUser, useMe } from './useAuth';

/** Renders the nested routes only for a signed-in user; otherwise sends them to `/login?next=…`. */
export function RequireAuth() {
  const location = useLocation();
  const me = useMe();

  if (me.isPending) {
    return <FullPageLoader />;
  }
  if (me.isError) {
    return <FullPageError error={me.error} onRetry={() => void me.refetch()} />;
  }
  if (!me.data) {
    const next = location.pathname + location.search;
    return <Navigate to={`/login?next=${encodeURIComponent(next)}`} replace />;
  }
  return (
    <CurrentUserContext.Provider value={me.data}>
      <Outlet />
    </CurrentUserContext.Provider>
  );
}

/** Hides a screen from other roles. Convenience only: the server checks every call itself. */
export function RequireRole({ role, children }: { role: Role; children: React.ReactNode }) {
  // From the guard's context, not the `me` query: on a lost session the query turns null first, and the
  // user must land on the login page, not on "forbidden".
  const me = useCurrentUser();
  if (me.role !== role) {
    return <Navigate to="/forbidden" replace />;
  }
  return children;
}
