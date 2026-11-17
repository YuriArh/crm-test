// @vitest-environment jsdom
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { cardPath, demoOperator, pendingCard, serveCard, serveOperators, withExchange } from '@/requests/card/testData';
import { demoInitiator } from '@/test/server';
import { renderApp } from '@/test/renderApp';
import { deliveryUnknown, invalidMessage, resolvedByOperator, resolvedBySystem, resultConflict } from './fixtures';

describe('problems in the request card', () => {
  it('shows open problems as notices, in the order dispatch sent them, coloured by kind', async () => {
    serveCard(withExchange(pendingCard({ problems: [resultConflict(), deliveryUnknown(), resolvedBySystem()] })));
    serveOperators();
    renderApp(cardPath, { me: demoOperator });

    const block = await screen.findByRole('region', { name: 'Проблемы' });
    const notices = within(block).getAllByRole('alert');
    expect(notices).toHaveLength(2);
    expect(within(notices[0]!).getByText('Расхождение итогов')).toBeInTheDocument();
    expect(notices[0]).toHaveAttribute('data-kind', 'RESULT_CONFLICT');
    expect(within(notices[1]!).getByText('Неизвестно, получена ли заявка')).toBeInTheDocument();
  });

  it('folds closed problems under their count and opens them on demand', async () => {
    const user = userEvent.setup();
    serveCard(withExchange(pendingCard({ problems: [invalidMessage(), resolvedByOperator(), resolvedBySystem()] })));
    serveOperators();
    renderApp(cardPath, { me: demoOperator });

    const toggle = await screen.findByRole('button', { name: 'Закрытые проблемы (2)' });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await user.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByRole('button', { name: /Расхождение итогов.*Оставлен наш итог/ })).toBeVisible();
    expect(screen.getByRole('button', { name: /Неизвестно, получена ли заявка.*Закрыта системой/ })).toBeVisible();
  });

  it('shows nothing when the request has no problems', async () => {
    serveCard(withExchange(pendingCard()));
    serveOperators();
    renderApp(cardPath, { me: demoOperator });

    await screen.findByRole('heading', { name: 'DR-000123', level: 1 });
    expect(screen.queryByRole('region', { name: 'Проблемы' })).not.toBeInTheDocument();
  });

  it('shows nothing to an initiator, who gets no problems', async () => {
    serveCard(pendingCard());
    renderApp(cardPath, { me: demoInitiator });

    await screen.findByRole('heading', { name: 'DR-000123', level: 1 });
    expect(screen.queryByRole('region', { name: 'Проблемы' })).not.toBeInTheDocument();
  });

  describe('on a phone', () => {
    const desktopMatchMedia = window.matchMedia;
    beforeEach(() => {
      window.matchMedia = (query: string) => ({ ...desktopMatchMedia(query), matches: query.startsWith('not all') });
    });
    afterEach(() => {
      window.matchMedia = desktopMatchMedia;
    });

    it('puts the problems right under the header, before the content', async () => {
      serveCard(withExchange(pendingCard({ problems: [deliveryUnknown()] })));
      serveOperators();
      renderApp(cardPath, { me: demoOperator });

      await screen.findByRole('region', { name: 'Проблемы' });
      const order = screen
        .getAllByRole('region')
        .map((region) => region.getAttribute('aria-label'))
        .filter((label) => label !== null);
      expect(order.slice(0, 2)).toEqual(['Проблемы', 'Содержимое']);
    });
  });
});
