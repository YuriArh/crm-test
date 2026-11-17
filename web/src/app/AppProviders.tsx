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
  /**
   * `test` turns off Mantine's transitions and portals (Mantine's testing guide): popovers and modals appear at
   * once instead of after an animation, which jsdom under load may not finish within a query's timeout.
   */
  env?: 'default' | 'test';
};

/** Theme, server state and routing around the app; main.tsx and tests render the same tree. */
export function AppProviders({ queryClient, router, env = 'default' }: Props) {
  return (
    <MantineProvider
      theme={theme}
      cssVariablesResolver={cssVariablesResolver}
      defaultColorScheme={DEFAULT_COLOR_SCHEME}
      env={env}
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
