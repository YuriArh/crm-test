import { useState } from 'react';
import { MantineProvider } from '@mantine/core';
import { DatesProvider } from '@mantine/dates';
import { Notifications } from '@mantine/notifications';
import { QueryClientProvider, type QueryClient } from '@tanstack/react-query';
import { createBrowserRouter, RouterProvider } from 'react-router';

import { routes } from './routes';
import { theme } from './theme';

export function Providers({
  queryClient,
  children,
}: {
  queryClient: QueryClient;
  children: React.ReactNode;
}) {
  return (
    <QueryClientProvider client={queryClient}>
      <MantineProvider theme={theme}>
        <DatesProvider settings={{ locale: 'ru', firstDayOfWeek: 1 }}>
          <Notifications position="top-right" />
          {children}
        </DatesProvider>
      </MantineProvider>
    </QueryClientProvider>
  );
}

export function App({ queryClient }: { queryClient: QueryClient }) {
  const [router] = useState(() => createBrowserRouter(routes));
  return (
    <Providers queryClient={queryClient}>
      <RouterProvider router={router} />
    </Providers>
  );
}
