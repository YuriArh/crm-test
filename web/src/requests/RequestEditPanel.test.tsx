import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';

import type { RequestCard, UpdateRequest } from '../api/types';
import { Providers } from '../App';
import { card } from '../test/fixtures';
import { problem, server } from '../test/server';
import { testQueryClient } from '../test/render';
import { RequestEditPanel } from './RequestEditPanel';

function renderPanel(version = 1, comment: string | null = null, overrides: Partial<RequestCard> = {}) {
  const user = userEvent.setup();
  render(
    <Providers queryClient={testQueryClient()}>
      <RequestEditPanel card={card({ version, comment, ...overrides })} canAssign={false} />
    </Providers>,
  );
  return user;
}

function recordPatches(respond: (body: UpdateRequest, call: number) => Response) {
  const bodies: UpdateRequest[] = [];
  server.use(
    http.patch('*/api/requests/:id', async ({ request }) => {
      const body = (await request.json()) as UpdateRequest;
      bodies.push(body);
      return respond(body, bodies.length);
    }),
  );
  return bodies;
}

describe('editing the comment', () => {
  it('sends only the changed field with the version the user started from', async () => {
    const bodies = recordPatches((body) =>
      HttpResponse.json(card({ version: 2, comment: body.comment ?? null })),
    );
    const user = renderPanel(1);

    await user.type(screen.getByLabelText('Комментарий'), '  Позвонить за час ');
    await user.click(screen.getByRole('button', { name: 'Сохранить' }));

    await waitFor(() => expect(bodies).toEqual([{ version: 1, comment: 'Позвонить за час' }]));
  });

  it('shows both values when someone else changed the same field, and saves only on request', async () => {
    const theirs = card({ version: 2, comment: 'Их комментарий' });
    const bodies = recordPatches((_, call) =>
      call === 1
        ? problem(409, 'VERSION_CONFLICT', { current: theirs })
        : HttpResponse.json(card({ version: 3 })),
    );
    const user = renderPanel(1);

    await user.type(screen.getByLabelText('Комментарий'), 'Мой комментарий');
    await user.click(screen.getByRole('button', { name: 'Сохранить' }));

    const alert = (await screen.findByText('Заявку изменил другой пользователь')).closest('[role="alert"]');
    expect(alert).toHaveTextContent('Заявку изменил другой пользователь');
    expect(alert).toHaveTextContent('сейчас — «Их комментарий», ваш вариант — «Мой комментарий»');
    expect(bodies).toHaveLength(1);

    await user.click(screen.getByRole('button', { name: 'Сохранить мой вариант' }));
    await waitFor(() => expect(bodies[1]).toEqual({ version: 2, comment: 'Мой комментарий' }));
  });

  it('re-sends on top of the new version when only other data changed', async () => {
    // E.g. a result arrived and bumped the version, but nobody touched the comment.
    const bodies = recordPatches((_, call) =>
      call === 1
        ? problem(409, 'VERSION_CONFLICT', { current: card({ version: 2, businessStatus: 'SUCCEEDED' }) })
        : HttpResponse.json(card({ version: 3, comment: 'Мой' })),
    );
    const user = renderPanel(1);

    await user.type(screen.getByLabelText('Комментарий'), 'Мой');
    await user.click(screen.getByRole('button', { name: 'Сохранить' }));

    await waitFor(() =>
      expect(bodies).toEqual([
        { version: 1, comment: 'Мой' },
        { version: 2, comment: 'Мой' },
      ]),
    );
    expect(screen.queryByText('Заявку изменил другой пользователь')).not.toBeInTheDocument();
  });

  it("never reverts another user's change to a field this user did not edit", async () => {
    const before = { id: '0195f000-0000-7000-8000-0000000000aa', displayName: 'Оператор X' };
    const after = { id: '0195f000-0000-7000-8000-0000000000bb', displayName: 'Оператор Y' };
    // Meanwhile someone else reassigned the request from X to Y; this user edits only the comment.
    const bodies = recordPatches((_, call) =>
      call === 1
        ? problem(409, 'VERSION_CONFLICT', { current: card({ version: 2, assignee: after }) })
        : HttpResponse.json(card({ version: 3, assignee: after, comment: 'Мой' })),
    );
    const user = renderPanel(1, null, { assignee: before });

    await user.type(screen.getByLabelText('Комментарий'), 'Мой');
    await user.click(screen.getByRole('button', { name: 'Сохранить' }));

    await waitFor(() =>
      expect(bodies).toEqual([
        { version: 1, comment: 'Мой' },
        { version: 2, comment: 'Мой' },
      ]),
    );
  });

  it('says so when the conflict cannot be shown because the card failed to load', async () => {
    recordPatches(() => problem(409, 'VERSION_CONFLICT'));
    server.use(http.get('*/api/requests/:id', () => HttpResponse.error()));
    const user = renderPanel(1);

    await user.type(screen.getByLabelText('Комментарий'), 'Мой');
    await user.click(screen.getByRole('button', { name: 'Сохранить' }));

    expect(await screen.findByText(/Не удалось загрузить её новую версию/)).toBeInTheDocument();
  });
});
