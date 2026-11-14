// @vitest-environment jsdom
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import dayjs from 'dayjs';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';
import type { Me } from '@/shared/api';
import { todayIn } from '@/shared/system';
import { renderApp } from '@/test/renderApp';
import { demoInitiator, server } from '@/test/server';

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

beforeEach(() => {
  server.use(
    http.get('*/api/system/info', () =>
      HttpResponse.json({ application: 'dispatch', serverTime: new Date().toISOString(), businessTimezone: 'Europe/Moscow' }),
    ),
  );
});

async function openForm() {
  const user = userEvent.setup();
  renderApp('/requests/new', { me: demoInitiator });
  await screen.findByRole('heading', { name: 'Новая заявка' });
  return user;
}

function dayLabel(date: string) {
  return dayjs(date).locale('ru').format('D MMMM YYYY');
}

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
    const user = await openForm();

    await user.click(screen.getByRole('button', { name: 'Создать заявку' }));

    expect(await screen.findByText('Опишите груз')).toBeInTheDocument();
    expect(screen.getByText('Укажите вес')).toBeInTheDocument();
    expect(screen.getByText('Укажите адрес')).toBeInTheDocument();
    expect(screen.getByText('Укажите срок доставки')).toBeInTheDocument();
  });

  it('shows errors for wrong values and counts characters', async () => {
    const user = await openForm();

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

  it('takes the weight with a dot or a comma and keeps one decimal', async () => {
    const user = await openForm();
    const weight = screen.getByRole('textbox', { name: 'Вес, кг' });

    await user.type(weight, '120.55');
    expect(weight).toHaveValue('120,5');

    await user.clear(weight);
    await user.type(weight, '7,3');
    expect(weight).toHaveValue('7,3');
  });

  it('offers today but not the days before it in the business time zone', async () => {
    const user = await openForm();

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

describe('CreateRequestPage: access', () => {
  it('answers "not found" to an operator', async () => {
    renderApp('/requests/new', { me: operator });

    expect(await screen.findByRole('heading', { name: 'Страница не найдена' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Новая заявка' })).not.toBeInTheDocument();
  });
});
