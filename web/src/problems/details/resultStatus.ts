import { BUSINESS_VIEWS } from '@/requests/statuses';

/** A result status as words; a record may carry anything, so an unknown value is shown as it came. */
export function resultStatusLabel(status: string): string {
  return (BUSINESS_VIEWS as Record<string, { label: string }>)[status]?.label ?? status;
}
