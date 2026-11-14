import type { CreateRequest, FieldError } from '@/shared/api';

/**
 * Field rules of the create form, the same as dispatch checks (`CreateRequestBody`, contract
 * `CreateRequest`). The client check is a convenience; the server's answer is the truth.
 */
export const CARGO_MAX = 200;
export const ADDRESS_MAX = 500;
export const WEIGHT_MIN = 0.1;
export const WEIGHT_MAX = 20_000;

/** What the form holds: `NumberInput` gives a number, or a string while the input is empty or incomplete. */
export type CreateFormValues = {
  cargoDescription: string;
  weightKg: number | string;
  deliveryAddress: string;
  /** `YYYY-MM-DD` as `DatePickerInput` gives it, or `null` while not picked. */
  deadline: string | null;
};

export type CreateField = keyof CreateFormValues;
export type CreateFormErrors = Partial<Record<CreateField, string>>;

export const EMPTY_FORM: CreateFormValues = { cargoDescription: '', weightKg: '', deliveryAddress: '', deadline: null };

export const MESSAGES = {
  cargoRequired: 'Опишите груз',
  cargoTooLong: `Не длиннее ${CARGO_MAX} символов`,
  weightRequired: 'Укажите вес',
  weightRange: 'Вес от 0,1 до 20 000 кг',
  weightScale: 'Не больше одного знака после запятой',
  addressRequired: 'Укажите адрес',
  addressTooLong: `Не длиннее ${ADDRESS_MAX} символов`,
  deadlineRequired: 'Укажите срок доставки',
  deadlinePast: 'Срок не раньше сегодня',
} as const;

/** The weight as a number: accepts a number or a string with a comma or a dot; `null` if it is not one. */
export function parseWeight(value: number | string): number | null {
  if (typeof value === 'number') return Number.isFinite(value) ? value : null;
  const text = value.trim().replace(',', '.');
  if (!/^\d+(\.\d+)?$/.test(text)) return null;
  return Number(text);
}

/** At most one decimal place, without floating-point noise (`0.3` passes, `1.25` does not). */
function hasOneDecimalAtMost(value: number): boolean {
  const tenths = value * 10;
  return Math.abs(tenths - Math.round(tenths)) < 1e-9;
}

function validateCargo(value: string): string | undefined {
  const text = value.trim();
  if (!text) return MESSAGES.cargoRequired;
  if (text.length > CARGO_MAX) return MESSAGES.cargoTooLong;
  return undefined;
}

function validateWeight(value: number | string): string | undefined {
  if (typeof value === 'string' && !value.trim()) return MESSAGES.weightRequired;
  const weight = parseWeight(value);
  if (weight === null || weight < WEIGHT_MIN || weight > WEIGHT_MAX) return MESSAGES.weightRange;
  if (!hasOneDecimalAtMost(weight)) return MESSAGES.weightScale;
  return undefined;
}

function validateAddress(value: string): string | undefined {
  const text = value.trim();
  if (!text) return MESSAGES.addressRequired;
  if (text.length > ADDRESS_MAX) return MESSAGES.addressTooLong;
  return undefined;
}

/** `today` is today's date in the business time zone (`todayIn`), not the browser's (NF07). */
function validateDeadline(value: string | null, today: string): string | undefined {
  if (!value) return MESSAGES.deadlineRequired;
  // Both are YYYY-MM-DD, so the string order is the date order.
  if (value < today) return MESSAGES.deadlinePast;
  return undefined;
}

/** Errors of the whole form; an empty object means it can be sent. */
export function validateCreate(values: CreateFormValues, today: string): CreateFormErrors {
  const errors: CreateFormErrors = {
    cargoDescription: validateCargo(values.cargoDescription),
    weightKg: validateWeight(values.weightKg),
    deliveryAddress: validateAddress(values.deliveryAddress),
    deadline: validateDeadline(values.deadline, today),
  };
  return Object.fromEntries(Object.entries(errors).filter(([, message]) => message !== undefined));
}

/** The body as it goes to dispatch: trimmed text, the weight as a number. Call only on a valid form. */
export function toCreateRequest(values: CreateFormValues): CreateRequest {
  const weight = parseWeight(values.weightKg);
  if (weight === null || !values.deadline) {
    throw new Error('toCreateRequest needs a validated form');
  }
  return {
    cargoDescription: values.cargoDescription.trim(),
    weightKg: weight,
    deliveryAddress: values.deliveryAddress.trim(),
    deadline: values.deadline,
  };
}

/** Russian text per field for `400 VALIDATION_FAILED`: the server's messages are English. */
const SERVER_FIELD_MESSAGES: Record<CreateField, string> = {
  cargoDescription: `Опишите груз, не длиннее ${CARGO_MAX} символов`,
  weightKg: 'Вес от 0,1 до 20 000 кг, не больше одного знака после запятой',
  deliveryAddress: `Укажите адрес, не длиннее ${ADDRESS_MAX} символов`,
  deadline: MESSAGES.deadlinePast,
};

function isCreateField(field: string): field is CreateField {
  return field in SERVER_FIELD_MESSAGES;
}

/** Lays the field errors of `VALIDATION_FAILED` out over the form fields; unknown fields are skipped. */
export function serverFieldErrors(errors: FieldError[]): CreateFormErrors {
  const result: CreateFormErrors = {};
  for (const { field } of errors) {
    if (isCreateField(field)) result[field] = SERVER_FIELD_MESSAGES[field];
  }
  return result;
}

/** Whether the user has typed or picked anything (cancelling a filled form asks first). */
export function isFilled(values: CreateFormValues): boolean {
  return (
    values.cargoDescription.trim() !== '' ||
    String(values.weightKg).trim() !== '' ||
    values.deliveryAddress.trim() !== '' ||
    values.deadline !== null
  );
}
