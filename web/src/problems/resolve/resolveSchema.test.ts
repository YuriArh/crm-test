import { describe, expect, it } from 'vitest';
import { COMMENT_MAX, validateComment } from './resolveSchema';

describe('validateComment', () => {
  it('asks for a ground when the comment is empty or blank', () => {
    expect(validateComment('')).toBe('Опишите основание решения');
    expect(validateComment('   \n ')).toBe('Опишите основание решения');
  });

  it('accepts up to 2000 characters and refuses more', () => {
    expect(COMMENT_MAX).toBe(2000);
    expect(validateComment('я'.repeat(2000))).toBeNull();
    expect(validateComment('я'.repeat(2001))).toBe('Не больше 2000 символов');
  });

  it('accepts an ordinary comment', () => {
    expect(validateComment('Перевозчик подтвердил прежний номер')).toBeNull();
  });
});
