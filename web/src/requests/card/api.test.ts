import { describe, expect, it } from 'vitest';
import { isFinalCard } from './api';
import { pendingCard, rejectedCard, succeededCard } from './testData';

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
