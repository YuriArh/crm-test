import { describe, expect, it } from 'vitest';
import { todayIn } from '@/shared/system';
import {
  EMPTY_FORM,
  isFilled,
  MESSAGES,
  parseWeight,
  serverFieldErrors,
  toCreateRequest,
  validateCreate,
  type CreateFormValues,
} from './createSchema';

const TODAY = '2026-11-14';
const valid: CreateFormValues = {
  cargoDescription: 'Паллеты',
  weightKg: 12.5,
  deliveryAddress: 'ул. Ленина, 1',
  deadline: '2026-12-01',
};

function errorsOf(change: Partial<CreateFormValues>, today = TODAY) {
  return validateCreate({ ...valid, ...change }, today);
}

describe('validateCreate', () => {
  it('passes a valid form', () => {
    expect(validateCreate(valid, TODAY)).toEqual({});
  });

  it('asks for every field of an empty form', () => {
    expect(validateCreate(EMPTY_FORM, TODAY)).toEqual({
      cargoDescription: MESSAGES.cargoRequired,
      weightKg: MESSAGES.weightRequired,
      deliveryAddress: MESSAGES.addressRequired,
      deadline: MESSAGES.deadlineRequired,
    });
  });

  it('treats blank text as empty', () => {
    expect(errorsOf({ cargoDescription: '   ', deliveryAddress: '\n ' })).toEqual({
      cargoDescription: MESSAGES.cargoRequired,
      deliveryAddress: MESSAGES.addressRequired,
    });
  });

  it.each([
    [0.1, undefined],
    [20_000, undefined],
    [1.2, undefined],
    [0.3, undefined],
    ['12,5', undefined],
    ['12.5', undefined],
    [0.05, MESSAGES.weightRange],
    [0, MESSAGES.weightRange],
    [20_000.1, MESSAGES.weightRange],
    [-1, MESSAGES.weightRange],
    ['abc', MESSAGES.weightRange],
    [1.25, MESSAGES.weightScale],
    ['1,25', MESSAGES.weightScale],
  ])('weight %s → %s', (weightKg, message) => {
    expect(errorsOf({ weightKg }).weightKg).toBe(message);
  });

  it('counts the length of the cargo without the spaces around it', () => {
    expect(errorsOf({ cargoDescription: 'г'.repeat(200) })).toEqual({});
    expect(errorsOf({ cargoDescription: `  ${'г'.repeat(200)}  ` })).toEqual({});
    expect(errorsOf({ cargoDescription: 'г'.repeat(201) }).cargoDescription).toBe(MESSAGES.cargoTooLong);
  });

  it('limits the address to 500 characters', () => {
    expect(errorsOf({ deliveryAddress: 'а'.repeat(500) })).toEqual({});
    expect(errorsOf({ deliveryAddress: 'а'.repeat(501) }).deliveryAddress).toBe(MESSAGES.addressTooLong);
  });

  it('accepts today and later, not yesterday', () => {
    expect(errorsOf({ deadline: '2026-11-13' }).deadline).toBe(MESSAGES.deadlinePast);
    expect(errorsOf({ deadline: '2026-11-14' })).toEqual({});
    expect(errorsOf({ deadline: '2026-11-15' })).toEqual({});
  });

  describe('"today" is the business day in Moscow, not the UTC day', () => {
    const moscowToday = (iso: string) => todayIn('Europe/Moscow', new Date(iso));

    it('at 01:00 UTC (04:00 in Moscow) both days are the same', () => {
      const today = moscowToday('2026-11-14T01:00:00Z');
      expect(today).toBe('2026-11-14');
      expect(errorsOf({ deadline: '2026-11-13' }, today).deadline).toBe(MESSAGES.deadlinePast);
      expect(errorsOf({ deadline: '2026-11-14' }, today)).toEqual({});
      expect(errorsOf({ deadline: '2026-11-15' }, today)).toEqual({});
    });

    it('at 23:30 UTC it is already tomorrow in Moscow, so the UTC date is in the past', () => {
      const today = moscowToday('2026-11-14T23:30:00Z');
      expect(today).toBe('2026-11-15');
      expect(errorsOf({ deadline: '2026-11-14' }, today).deadline).toBe(MESSAGES.deadlinePast);
      expect(errorsOf({ deadline: '2026-11-15' }, today)).toEqual({});
      expect(errorsOf({ deadline: '2026-11-16' }, today)).toEqual({});
    });

    it('at 01:00 in Moscow (22:00 UTC the day before) the browser in UTC is a day behind', () => {
      const today = moscowToday('2026-11-13T22:00:00Z');
      expect(today).toBe('2026-11-14');
      expect(errorsOf({ deadline: '2026-11-13' }, today).deadline).toBe(MESSAGES.deadlinePast);
    });
  });
});

describe('parseWeight', () => {
  it('reads a comma or a dot and rejects other text', () => {
    expect(parseWeight('120,5')).toBe(120.5);
    expect(parseWeight('120.5')).toBe(120.5);
    expect(parseWeight(7)).toBe(7);
    expect(parseWeight('')).toBeNull();
    expect(parseWeight('12,')).toBeNull();
    expect(parseWeight('1e3')).toBeNull();
  });
});

describe('toCreateRequest', () => {
  it('trims the text and sends the weight as a number', () => {
    expect(
      toCreateRequest({
        cargoDescription: '  Паллеты ',
        weightKg: '120,5',
        deliveryAddress: ' ул. Ленина, 1\n',
        deadline: '2026-12-01',
      }),
    ).toEqual({ cargoDescription: 'Паллеты', weightKg: 120.5, deliveryAddress: 'ул. Ленина, 1', deadline: '2026-12-01' });
  });

  it('refuses an unvalidated form', () => {
    expect(() => toCreateRequest(EMPTY_FORM)).toThrow();
  });
});

describe('serverFieldErrors', () => {
  it('shows Russian text under the known fields and skips the rest', () => {
    const errors = serverFieldErrors([
      { field: 'weightKg', message: 'must be between 0.1 and 20000 kg' },
      { field: 'deadline', message: 'must not be before today' },
      { field: 'somethingElse', message: 'x' },
    ]);
    expect(errors).toEqual({
      weightKg: 'Вес от 0,1 до 20 000 кг, не больше одного знака после запятой',
      deadline: MESSAGES.deadlinePast,
    });
  });
});

describe('isFilled', () => {
  it('is false for an empty or blank form and true once anything is entered', () => {
    expect(isFilled(EMPTY_FORM)).toBe(false);
    expect(isFilled({ ...EMPTY_FORM, cargoDescription: '  ' })).toBe(false);
    expect(isFilled({ ...EMPTY_FORM, weightKg: 0.1 })).toBe(true);
    expect(isFilled({ ...EMPTY_FORM, deadline: '2026-12-01' })).toBe(true);
    expect(isFilled({ ...EMPTY_FORM, deliveryAddress: 'а' })).toBe(true);
  });
});
