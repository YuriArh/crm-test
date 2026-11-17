/** The contract's limit of a decision's comment (`ResolveProblem.comment`, stored stripped). */
export const COMMENT_MAX = 2000;

/** The comment's error in the form's own words, or `null`: dispatch's field messages are English. */
export function validateComment(value: string): string | null {
  const comment = value.trim();
  if (comment === '') return 'Опишите основание решения';
  if (comment.length > COMMENT_MAX) return `Не больше ${COMMENT_MAX} символов`;
  return null;
}
