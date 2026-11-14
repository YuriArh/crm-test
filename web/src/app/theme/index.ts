// The design system: tokens → Mantine theme + CSS variables. MantineProvider takes all three exports.
import './global.css';

export { theme } from './theme';
export { cssVariablesResolver } from './cssVariables';

/** Dark first, like Linear; the user's choice (header switch) is kept in localStorage by Mantine. */
export const DEFAULT_COLOR_SCHEME = 'dark' as const;
