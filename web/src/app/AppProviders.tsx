import { MantineProvider } from '@mantine/core';
import { DatesProvider } from '@mantine/dates';
import { Notifications } from '@mantine/notifications';
import 'dayjs/locale/ru';
import { QueryClientProvider, type QueryClient } from '@tanstack/react-query';
import { RouterProvider } from '@tanstack/react-router';
import type { createAppRouter } from './router';
import { cssVariablesResolver, DEFAULT_COLOR_SCHEME, theme } from './theme';

type Props = {
  queryClient: QueryClient;
  router: ReturnType<typeof createAppRouter>;
};

/** Theme, server state and routing around the app; main.tsx and tests render the same tree. */
export function AppProviders({ queryClient, router }: Props) {
  return (
    <MantineProvider
      theme={theme}
      cssVariablesResolver={cssVariablesResolver}
      defaultColorScheme={DEFAULT_COLOR_SCHEME}
    >
      {/* Calendars in Russian, weeks from Monday. */}
      <DatesProvider settings={{ locale: 'ru' }}>
        <Notifications position="top-right" />
        <QueryClientProvider client={queryClient}>
          <RouterProvider router={router} />
        </QueryClientProvider>
      </DatesProvider>
    </MantineProvider>
  );
}
