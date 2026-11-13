import { z } from 'zod';

const WEIGHT_RANGE = 'Вес — от 0,1 до 20 000 кг';

/**
 * Client-side copy of the server rules of `POST /api/requests`, for quick feedback only: the server
 * checks the same rules and stays the source of truth. `today` is in the business time zone.
 */
export function createRequestSchema(today: string) {
  return z.object({
    cargoDescription: z.string().trim().min(1, 'Опишите груз').max(200, 'Не больше 200 символов'),
    weightKg: z
      .number({ error: 'Укажите вес' })
      .min(0.1, WEIGHT_RANGE)
      .max(20000, WEIGHT_RANGE)
      .refine(
        (value) => Math.abs(Math.round(value * 10) - value * 10) < 1e-6,
        'Не больше одного знака после запятой',
      ),
    deliveryAddress: z.string().trim().min(1, 'Укажите адрес доставки').max(500, 'Не больше 500 символов'),
    deadline: z
      .string({ error: 'Укажите срок' })
      .regex(/^\d{4}-\d{2}-\d{2}$/, 'Укажите срок')
      .refine((date) => date >= today, 'Срок не может быть раньше сегодняшнего дня'),
  });
}

export type CreateRequestForm = z.input<ReturnType<typeof createRequestSchema>>;
export type CreateRequestValues = z.output<ReturnType<typeof createRequestSchema>>;

/** Russian text for the field messages the server sends (it answers in English). */
export function translateServerMessage(message: string): string {
  if (message === 'must not be blank' || message === 'must not be null') return 'Обязательное поле';
  if (message.startsWith('size must be')) return 'Слишком длинное значение';
  if (message.startsWith('must be between 0.1 and 20000')) return WEIGHT_RANGE;
  if (message.startsWith('must have at most one decimal place'))
    return 'Не больше одного знака после запятой';
  if (message.includes('before today')) return 'Срок не может быть раньше сегодняшнего дня';
  return message;
}
