import { describe, expect, it } from 'vitest';

import { card } from '../test/fixtures';
import { deliveryView, shouldPoll } from './statuses';

describe('statuses', () => {
  it('hides exchange details from initiators but shows them to operators', () => {
    expect(deliveryView('UNKNOWN', 'INITIATOR').label).toBe('Отправляется');
    expect(deliveryView('RETRY_WAIT', 'INITIATOR').label).toBe('Отправляется');
    expect(deliveryView('UNKNOWN', 'OPERATOR').label).toBe('Неизвестно, получена ли');
  });

  it('polls until the outcome is final', () => {
    expect(shouldPoll(card({ businessStatus: 'AWAITING', deliveryStatus: 'PENDING' }))).toBe(true);
    expect(shouldPoll(card({ businessStatus: 'IN_PROGRESS' }))).toBe(true);
    expect(shouldPoll(card({ businessStatus: 'SUCCEEDED' }))).toBe(false);
    expect(shouldPoll(card({ businessStatus: 'REJECTED' }))).toBe(false);
    expect(shouldPoll(card({ deliveryStatus: 'FAILED' }))).toBe(false);
    expect(shouldPoll(undefined)).toBe(false);
  });
});
