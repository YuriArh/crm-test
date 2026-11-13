import { screen, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { renderAt } from '../test/render';

describe('create form', () => {
  it('explains every missing field in Russian before calling the server', async () => {
    const { user } = renderAt('/requests/new');
    const submit = await screen.findByRole('button', { name: 'Создать' });
    // Enabled once the business time zone is known.
    await waitFor(() => expect(submit).toBeEnabled());
    await user.click(submit);

    expect(await screen.findByText('Опишите груз')).toBeInTheDocument();
    expect(screen.getByText('Укажите вес')).toBeInTheDocument();
    expect(screen.getByText('Укажите адрес доставки')).toBeInTheDocument();
    expect(screen.getByText('Укажите срок')).toBeInTheDocument();
  });

  it('says so instead of silently changing a weight out of range', async () => {
    const { user } = renderAt('/requests/new');
    await user.type(await screen.findByLabelText(/Вес, кг/), '20001');
    const submit = screen.getByRole('button', { name: 'Создать' });
    await waitFor(() => expect(submit).toBeEnabled());
    await user.click(submit);
    expect(await screen.findByText('Вес — от 0,1 до 20 000 кг')).toBeInTheDocument();
  });
});
