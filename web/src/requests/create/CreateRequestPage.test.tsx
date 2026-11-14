// @vitest-environment jsdom
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import dayjs from 'dayjs';
import { delay, http, HttpResponse } from 'msw';
import { notifications } from '@mantine/notifications';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { requestKeys } from '@/requests/keys';
import type { CreateRequest, Me, RequestCard } from '@/shared/api';
import { todayIn } from '@/shared/system';
import { renderApp } from '@/test/renderApp';
import { demoInitiator, problem, server } from '@/test/server';

const operator: Me = {
  ...demoInitiator,
  id: '0195f000-0000-7000-8000-000000000102',
  username: 'north.operator',
  displayName: 'Оператор (Север)',
  role: 'OPERATOR',
};

const today = todayIn('Europe/Moscow');

// The autosizing Textarea listens to document.fonts, which jsdom does not have.
if (!('fonts' in document)) {
  Object.defineProperty(document, 'fonts', {
    configurable: true,
    value: { addEventListener: () => {}, removeEventListener: () => {} },
  });
}

// Notifications live in a module-level store that outlives the rendered app.
afterEach(() => notifications.clean());

beforeEach(() => {
  server.use(
    http.get('*/api/system/info', () =>
      HttpResponse.json({ application: 'dispatch', serverTime: new Date().toISOString(), businessTimezone: 'Europe/Moscow' }),
    ),
  );
});

async function openForm() {
  const user = userEvent.setup();
  const rendered = renderApp('/requests/new', { me: demoInitiator });
  await screen.findByRole('heading', { name: 'Новая заявка' });
  return { user, ...rendered };
}

function dayLabel(date: string) {
  return dayjs(date).locale('ru').format('D MMMM YYYY');
}

type User = ReturnType<typeof userEvent.setup>;

/** Fills the form with valid content; the deadline is today in the business zone. */
async function fillValid(user: User, cargo = '  Паллеты с плиткой ', weight = '120,5') {
  // The date first: the calendar does not open in jsdom right after typing into the other fields.
  await user.click(screen.getByRole('button', { name: 'Срок доставки' }));
  await user.click(await screen.findByRole('button', { name: dayLabel(today) }));
  await user.type(screen.getByRole('textbox', { name: 'Груз' }), cargo);
  if (weight) await user.type(screen.getByRole('textbox', { name: 'Вес, кг' }), weight);
  await user.type(screen.getByRole('textbox', { name: 'Адрес доставки' }), 'ул. Ленина, 1, склад 3');
}

async function openConfirm(user: User) {
  await user.click(screen.getByRole('button', { name: 'Создать заявку' }));
  return screen.findByRole('dialog', { name: 'Проверьте данные' });
}

const CREATED_ID = '0195f3a2-7c41-7d10-9a5e-3f1c2b4d5e60';

function card(body: CreateRequest) {
  return {
    id: CREATED_ID,
    number: 'DR-000129',
    ...body,
    comment: null,
    owner: { id: demoInitiator.id, displayName: demoInitiator.displayName },
    assignee: null,
    businessStatus: 'AWAITING',
    result: null,
    deliveryStatus: 'PENDING',
    operationId: '0195f3a2-7c41-7d10-9a5e-3f1c2b4d5e6f',
    overdue: false,
    createdAt: '2026-11-14T09:15:03Z',
    updatedAt: '2026-11-14T09:15:03Z',
    version: 0,
  } satisfies RequestCard;
}

type Sent = { body: CreateRequest; key: string | null };

/** Records every POST /api/requests and answers with `answer` (by attempt number, from 0). */
function recordCreates(answer: (attempt: number, sent: Sent) => Response | Promise<Response>) {
  const sent: Sent[] = [];
  server.use(
    http.post('*/api/requests', async ({ request }) => {
      const entry = { body: (await request.json()) as CreateRequest, key: request.headers.get('Idempotency-Key') };
      sent.push(entry);
      return answer(sent.length - 1, entry);
    }),
  );
  return sent;
}

const created = (_: number, { body }: Sent) => HttpResponse.json(card(body), { status: 201 });

describe('CreateRequestPage: form', () => {
  it('shows the fields, the immutability hint and the buttons', async () => {
    await openForm();

    expect(screen.getByRole('textbox', { name: 'Груз' })).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: 'Вес, кг' })).toHaveAttribute('inputmode', 'decimal');
    expect(screen.getByRole('textbox', { name: 'Адрес доставки' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Срок доставки' })).toBeInTheDocument();
    expect(screen.getByText('После создания заявка сразу уходит перевозчику, изменить её нельзя.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Создать заявку' })).toBeInTheDocument();
    expect(screen.getAllByText('0/200')).toHaveLength(1);
    expect(screen.getByText('0/500')).toBeInTheDocument();
  });

  it('shows an error under every field of an empty form', async () => {
    const { user } = await openForm();

    await user.click(screen.getByRole('button', { name: 'Создать заявку' }));

    expect(await screen.findByText('Опишите груз')).toBeInTheDocument();
    expect(screen.getByText('Укажите вес')).toBeInTheDocument();
    expect(screen.getByText('Укажите адрес')).toBeInTheDocument();
    expect(screen.getByText('Укажите срок доставки')).toBeInTheDocument();
  });

  it('shows errors for wrong values and counts characters', async () => {
    const { user } = await openForm();

    await user.click(screen.getByRole('textbox', { name: 'Груз' }));
    await user.paste('г'.repeat(201));
    await user.type(screen.getByRole('textbox', { name: 'Вес, кг' }), '0');
    await user.type(screen.getByRole('textbox', { name: 'Адрес доставки' }), '   ');
    await user.click(screen.getByRole('button', { name: 'Создать заявку' }));

    expect(await screen.findByText('Не длиннее 200 символов')).toBeInTheDocument();
    expect(screen.getByText('201/200')).toBeInTheDocument();
    expect(screen.getByText('Вес от 0,1 до 20 000 кг')).toBeInTheDocument();
    expect(screen.getByText('Укажите адрес')).toBeInTheDocument();
  });

  it('allows one decimal place at most', async () => {
    const { user } = await openForm();

    await user.type(screen.getByRole('textbox', { name: 'Вес, кг' }), '1,25');
    await user.click(screen.getByRole('button', { name: 'Создать заявку' }));

    expect(await screen.findByText('Не больше одного знака после запятой')).toBeInTheDocument();
  });

  it.each([
    ['typed with a dot', '7.3'],
    ['pasted with a dot', '7.3'],
    ['typed with a comma', '7,3'],
  ])('takes the weight %s and sends it as a number', async (how, text) => {
    const sent = recordCreates(created);
    const { user } = await openForm();
    await fillValid(user, 'Паллеты', '');
    const weight = screen.getByRole('textbox', { name: 'Вес, кг' });
    if (how.startsWith('pasted')) {
      await user.click(weight);
      await user.paste(text);
    } else {
      await user.type(weight, text);
    }

    const dialog = await openConfirm(user);
    expect(within(dialog).getByText('7,3 кг')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Создать' }));

    await waitFor(() => expect(sent).toHaveLength(1));
    expect(sent[0]!.body.weightKg).toBe(7.3);
  });

  it('offers today but not the days before it in the business time zone', async () => {
    const { user } = await openForm();

    await user.click(screen.getByRole('button', { name: 'Срок доставки' }));

    const todayButton = await screen.findByRole('button', { name: dayLabel(today) });
    expect(todayButton).not.toHaveAttribute('data-disabled');
    const yesterday = screen.queryByRole('button', {
      name: dayLabel(dayjs(today).subtract(1, 'day').format('YYYY-MM-DD')),
    });
    // The day before is hidden only when today opens the calendar on a Monday, the 1st.
    if (yesterday) expect(yesterday).toHaveAttribute('data-disabled', 'true');

    await user.click(todayButton);
    expect(screen.getByRole('button', { name: 'Срок доставки' })).toHaveTextContent(dayjs(today).format('DD.MM.YYYY'));
  });
});

describe('CreateRequestPage: confirmation and sending', () => {
  it('shows the content as it will be sent, and «Изменить» closes without a request', async () => {
    const sent = recordCreates(created);
    const { user } = await openForm();
    await fillValid(user);

    const dialog = await openConfirm(user);

    expect(within(dialog).getByText('Паллеты с плиткой')).toBeInTheDocument();
    expect(within(dialog).getByText('120,5 кг')).toBeInTheDocument();
    expect(within(dialog).getByText('ул. Ленина, 1, склад 3')).toBeInTheDocument();
    expect(within(dialog).getByText(dayjs(today).format('DD.MM.YYYY'))).toBeInTheDocument();
    expect(within(dialog).getByText(/сразу уходит перевозчику, изменить её будет нельзя/)).toBeInTheDocument();

    await user.click(within(dialog).getByRole('button', { name: 'Изменить' }));

    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Проверьте данные' })).not.toBeInTheDocument());
    expect(screen.getByRole('textbox', { name: 'Груз' })).toHaveValue('  Паллеты с плиткой ');
    expect(sent).toHaveLength(0);
  });

  it('creates the request, says so and opens its card; the registry in the cache is stale', async () => {
    const sent = recordCreates(async (attempt, entry) => {
      await delay(50);
      return created(attempt, entry);
    });
    const { user, history, queryClient } = await openForm();
    queryClient.setQueryData(requestKeys.list({ page: 0 }), { items: [] });
    await fillValid(user);
    const dialog = await openConfirm(user);

    await user.click(within(dialog).getByRole('button', { name: 'Создать' }));

    // One press, one request: while sending «Создать» is disabled and the window stays open.
    expect(within(dialog).getByRole('button', { name: 'Создать' })).toBeDisabled();
    expect(within(dialog).getByRole('button', { name: 'Изменить' })).toBeDisabled();
    expect(await screen.findByText('Заявка DR-000129 создана')).toBeInTheDocument();
    await waitFor(() => expect(history.location.pathname).toBe(`/requests/${CREATED_ID}`));
    expect(sent).toHaveLength(1);
    expect(sent[0]!.body).toEqual({
      cargoDescription: 'Паллеты с плиткой',
      weightKg: 120.5,
      deliveryAddress: 'ул. Ленина, 1, склад 3',
      deadline: today,
    });
    expect(sent[0]!.key).toMatch(/^[0-9a-f-]{36}$/);
    expect(queryClient.getQueryState(requestKeys.list({ page: 0 }))?.isInvalidated).toBe(true);
  });

  it('repeats with the same Idempotency-Key after a lost answer and keeps the form', async () => {
    const sent = recordCreates((attempt, entry) => (attempt === 0 ? HttpResponse.error() : created(attempt, entry)));
    const { user, history } = await openForm();
    await fillValid(user);

    await user.click(within(await openConfirm(user)).getByRole('button', { name: 'Создать' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Не удалось создать заявку. Проверьте связь и повторите.');
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Проверьте данные' })).not.toBeInTheDocument());
    expect(screen.getByRole('textbox', { name: 'Груз' })).toHaveValue('  Паллеты с плиткой ');

    await user.click(within(await openConfirm(user)).getByRole('button', { name: 'Создать' }));

    await waitFor(() => expect(history.location.pathname).toBe(`/requests/${CREATED_ID}`));
    expect(sent).toHaveLength(2);
    expect(sent[1]!.key).toBe(sent[0]!.key);
  });

  it('treats a 5xx like a lost answer', async () => {
    recordCreates(() => problem(503, 'SERVICE_UNAVAILABLE'));
    const { user } = await openForm();
    await fillValid(user);

    await user.click(within(await openConfirm(user)).getByRole('button', { name: 'Создать' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Не удалось создать заявку. Проверьте связь и повторите.');
  });

  it('sends a new key once the content has changed', async () => {
    const sent = recordCreates((attempt, entry) => (attempt === 0 ? HttpResponse.error() : created(attempt, entry)));
    const { user } = await openForm();
    await fillValid(user);
    await user.click(within(await openConfirm(user)).getByRole('button', { name: 'Создать' }));
    await screen.findByRole('alert');

    await user.type(screen.getByRole('textbox', { name: 'Груз' }), ' и клеем');
    await user.click(within(await openConfirm(user)).getByRole('button', { name: 'Создать' }));

    await waitFor(() => expect(sent).toHaveLength(2));
    expect(sent[1]!.body.cargoDescription).toBe('Паллеты с плиткой  и клеем');
    expect(sent[1]!.key).not.toBe(sent[0]!.key);
  });

  it('shows the server field errors under the fields', async () => {
    recordCreates(() =>
      problem(400, 'VALIDATION_FAILED', {
        errors: [
          { field: 'deadline', message: 'must not be before today' },
          { field: 'weightKg', message: 'must be between 0.1 and 20000 kg' },
        ],
      }),
    );
    const { user } = await openForm();
    await fillValid(user);

    await user.click(within(await openConfirm(user)).getByRole('button', { name: 'Создать' }));

    expect(await screen.findByText('Срок не раньше сегодня')).toBeInTheDocument();
    expect(screen.getByText('Вес от 0,1 до 20 000 кг, не больше одного знака после запятой')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Проверьте данные' })).not.toBeInTheDocument());
  });

  it('asks to send again with a new key after IDEMPOTENCY_KEY_REUSED', async () => {
    const sent = recordCreates((attempt, entry) =>
      attempt === 0 ? problem(422, 'IDEMPOTENCY_KEY_REUSED') : created(attempt, entry),
    );
    const { user, history } = await openForm();
    await fillValid(user);

    await user.click(within(await openConfirm(user)).getByRole('button', { name: 'Создать' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Повторите отправку.');

    await user.click(within(await openConfirm(user)).getByRole('button', { name: 'Создать' }));

    await waitFor(() => expect(history.location.pathname).toBe(`/requests/${CREATED_ID}`));
    expect(sent[1]!.key).not.toBe(sent[0]!.key);
  });

  it('says that only initiators create requests on 403', async () => {
    recordCreates(() => problem(403, 'FORBIDDEN'));
    const { user } = await openForm();
    await fillValid(user);

    await user.click(within(await openConfirm(user)).getByRole('button', { name: 'Создать' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Создавать заявки может только инициатор.');
  });
});

describe('CreateRequestPage: cancel', () => {
  it('goes straight back to the registry from an empty form', async () => {
    const { user, history } = await openForm();

    await user.click(screen.getByRole('link', { name: 'Отмена' }));

    await waitFor(() => expect(history.location.pathname).toBe('/requests'));
    expect(screen.queryByRole('dialog', { name: 'Отменить создание?' })).not.toBeInTheDocument();
  });

  it('asks before leaving a filled form and stays on «Продолжить заполнение»', async () => {
    const { user, history } = await openForm();
    await user.type(screen.getByRole('textbox', { name: 'Груз' }), 'Паллеты');

    await user.click(screen.getByRole('link', { name: 'Отмена' }));

    const dialog = await screen.findByRole('dialog', { name: 'Отменить создание?' });
    expect(within(dialog).getByText('Введённые данные пропадут.')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Продолжить заполнение' }));

    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Отменить создание?' })).not.toBeInTheDocument());
    expect(history.location.pathname).toBe('/requests/new');
    expect(screen.getByRole('textbox', { name: 'Груз' })).toHaveValue('Паллеты');
  });

  it('leaves a filled form on «Отменить создание», also from the back link', async () => {
    const { user, history } = await openForm();
    await user.type(screen.getByRole('textbox', { name: 'Вес, кг' }), '5');

    await user.click(screen.getByRole('link', { name: 'Заявки' }));
    const dialog = await screen.findByRole('dialog', { name: 'Отменить создание?' });
    await user.click(within(dialog).getByRole('button', { name: 'Отменить создание' }));

    await waitFor(() => expect(history.location.pathname).toBe('/requests'));
  });
});

describe('CreateRequestPage: access', () => {
  it('answers "not found" to an operator', async () => {
    renderApp('/requests/new', { me: operator });

    expect(await screen.findByRole('heading', { name: 'Страница не найдена' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Новая заявка' })).not.toBeInTheDocument();
  });
});
