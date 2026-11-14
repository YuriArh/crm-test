import { createTheme } from '@mantine/core';

// The single Mantine theme of the app. Modules use Mantine components and these tokens instead of
// their own colours and sizes.
export const theme = createTheme({
  primaryColor: 'blue',
  defaultRadius: 'sm',
});
