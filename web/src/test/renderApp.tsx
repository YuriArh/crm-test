import { createMemoryHistory } from '@tanstack/react-router';
import { render } from '@testing-library/react';
import { AppProviders } from '@/app/AppProviders';
import { createApp } from '@/app/createApp';
import { meKey } from '@/auth/keys';
import type { Me } from '@/shared/api';

/**
 * Renders the whole app at `path`, wired as in main.tsx, with an in-memory history. `me` answers the
 * current-user query up front (`null` = nobody signed in), so only the requests under test reach MSW.
 */
export function renderApp(path: string, { me }: { me: Me | null }) {
  const history = createMemoryHistory({ initialEntries: [path] });
  const { queryClient, router } = createApp({ history });
  queryClient.setQueryData(meKey, me);

  render(<AppProviders queryClient={queryClient} router={router} />);
  return { queryClient, router, history };
}
