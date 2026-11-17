import { describe, expect, it } from 'vitest';
import { deliveryFailed, resolvedBySystem } from '@/problems/fixtures';
import { ApiError } from '@/shared/api';
import { CARD_REFRESH_MS, cardRefreshInterval, isFinalCard } from './api';
import { pendingCard, rejectedCard, succeededCard, withExchange } from './testData';

describe('cardRefreshInterval', () => {
  it('asks again every 5 s while the card is not final', () => {
    expect(cardRefreshInterval(pendingCard(), null)).toBe(CARD_REFRESH_MS);
  });

  it('stops asking once the card is final and nothing is open', () => {
    expect(cardRefreshInterval(succeededCard(), null)).toBe(false);
    expect(cardRefreshInterval(withExchange(succeededCard({ problems: [resolvedBySystem()] })), null)).toBe(false);
  });

  it('keeps asking a final card while a problem is open: a retry or the system may still change it', () => {
    const failed = withExchange(succeededCard({ deliveryStatus: 'FAILED', problems: [deliveryFailed()] }));
    expect(cardRefreshInterval(failed, null)).toBe(CARD_REFRESH_MS);
  });

  it('does not ask before the first answer or after the request left the scope', () => {
    expect(cardRefreshInterval(undefined, null)).toBe(false);
    expect(cardRefreshInterval(pendingCard(), new ApiError(404, undefined))).toBe(false);
  });
});

describe('isFinalCard', () => {
  it('is final only when both the result and the delivery are final', () => {
    expect(isFinalCard(succeededCard())).toBe(true);
    expect(isFinalCard(rejectedCard())).toBe(true);
    expect(isFinalCard(pendingCard())).toBe(false);
    // A result can come before the receipt is confirmed: keep asking until delivery settles too.
    expect(isFinalCard(succeededCard({ deliveryStatus: 'UNKNOWN' }))).toBe(false);
    expect(isFinalCard(pendingCard({ businessStatus: 'AWAITING', deliveryStatus: 'ACKNOWLEDGED' }))).toBe(false);
  });
});
