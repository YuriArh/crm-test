import { Navigate, type RouteObject } from 'react-router';

import { RequireAuth, RequireRole } from './auth/RequireAuth';
import { AppLayout } from './layout/AppLayout';
import { LoginPage } from './pages/LoginPage';
import { ForbiddenPage, NotFoundPage } from './pages/SimplePages';
import { RequestCardPage } from './requests/RequestCardPage';
import { RequestCreatePage } from './requests/RequestCreatePage';
import { RequestListPage } from './requests/RequestListPage';

export const routes: RouteObject[] = [
  { path: '/login', element: <LoginPage /> },
  {
    element: <RequireAuth />,
    children: [
      {
        element: <AppLayout />,
        children: [
          { index: true, element: <Navigate to="/requests" replace /> },
          { path: '/requests', element: <RequestListPage /> },
          {
            path: '/requests/new',
            element: (
              <RequireRole role="INITIATOR">
                <RequestCreatePage />
              </RequireRole>
            ),
          },
          { path: '/requests/:id', element: <RequestCardPage /> },
          { path: '/forbidden', element: <ForbiddenPage /> },
          { path: '*', element: <NotFoundPage /> },
        ],
      },
    ],
  },
];
