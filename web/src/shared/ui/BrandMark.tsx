import { Box } from '@mantine/core';

/** The Dispatch mark: a route between two points on an accent tile. */
export function BrandMark({ size = 24 }: { size?: number }) {
  return (
    <Box
      component="svg"
      viewBox="0 0 24 24"
      w={size}
      h={size}
      aria-hidden
      style={{ flexShrink: 0, display: 'block' }}
    >
      <rect width="24" height="24" rx="6" fill="var(--mantine-color-accent-6)" />
      <path
        d="M7 16.5c0-3 2-4.5 5-4.5s5-1.5 5-4.5"
        fill="none"
        stroke="#fff"
        strokeWidth="1.8"
        strokeLinecap="round"
      />
      <circle cx="7" cy="16.5" r="1.9" fill="#fff" />
      <circle cx="17" cy="7.5" r="1.9" fill="#fff" />
    </Box>
  );
}
