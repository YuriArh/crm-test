// @vitest-environment jsdom
import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderInRouter } from '@/test/renderInRouter';
import { ResultComparison } from './ResultComparison';

const OURS = { status: 'SUCCEEDED', trackingNumber: 'TRK-00000001', price: 325, reason: null };
const CARRIERS = { status: 'SUCCEEDED', trackingNumber: 'TRK-00000002', price: 400, reason: null };

describe('ResultComparison', () => {
  it('shows both sides under their titles and marks only the differing fields in words', async () => {
    renderInRouter(
      <ResultComparison
        left={{ title: 'У перевозчика', result: CARRIERS, empty: 'Итога нет' }}
        right={{ title: 'У нас', result: OURS, empty: 'Итога нет' }}
      />,
    );

    const carrier = await screen.findByRole('region', { name: 'У перевозчика' });
    const ours = screen.getByRole('region', { name: 'У нас' });
    expect(within(carrier).getByText('TRK-00000002')).toBeInTheDocument();
    expect(within(ours).getByText('TRK-00000001')).toBeInTheDocument();
    expect(within(carrier).getAllByText('отличается')).toHaveLength(2);
    expect(within(ours).queryByText('отличается')).not.toBeInTheDocument();
  });

  it('says a side has no result and still lists the other one', async () => {
    renderInRouter(
      <ResultComparison
        left={{ title: 'У перевозчика', result: null, empty: 'У перевозчика итога ещё нет' }}
        right={{ title: 'У нас', result: OURS, empty: 'Итога нет' }}
      />,
    );

    const carrier = await screen.findByRole('region', { name: 'У перевозчика' });
    expect(within(carrier).getByText('У перевозчика итога ещё нет')).toBeInTheDocument();
    expect(within(screen.getByRole('region', { name: 'У нас' })).getByText('TRK-00000001')).toBeInTheDocument();
  });

  it('does not compare when told not to: the right side is not of the same moment', async () => {
    renderInRouter(
      <ResultComparison
        left={{ title: 'У перевозчика', result: CARRIERS, empty: 'Итога нет' }}
        right={{ title: 'Наш итог сейчас', result: OURS, empty: 'Итога нет', note: 'снимок не сохранён' }}
        compare={false}
      />,
    );

    const ours = await screen.findByRole('region', { name: 'Наш итог сейчас' });
    expect(within(ours).getByText('снимок не сохранён')).toBeInTheDocument();
    expect(screen.queryByText('отличается')).not.toBeInTheDocument();
  });
});
