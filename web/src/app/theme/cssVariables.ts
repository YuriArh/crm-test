import type { CSSVariablesResolver } from '@mantine/core';
import { schemes, statusColors } from './tokens';

type Scheme = (typeof schemes)[keyof typeof schemes];

/** Mantine's semantic variables plus our `--app-*` variables for one scheme. */
function schemeVariables(scheme: Scheme): Record<string, string> {
  return {
    '--mantine-color-body': scheme.body,
    '--mantine-color-text': scheme.text,
    '--mantine-color-bright': scheme.text,
    '--mantine-color-dimmed': scheme.textTertiary,
    '--mantine-color-placeholder': scheme.placeholder,
    '--mantine-color-default': scheme.control,
    '--mantine-color-default-hover': scheme.controlHover,
    '--mantine-color-default-color': scheme.text,
    '--mantine-color-default-border': scheme.border,
    '--mantine-color-error': statusColors.red,
    '--mantine-color-success': statusColors.green,
    '--mantine-color-anchor': 'var(--mantine-color-accent-4)',

    '--app-color-surface': scheme.surface,
    '--app-color-surface-raised': scheme.surfaceRaised,
    '--app-color-border': scheme.border,
    '--app-color-border-strong': scheme.borderStrong,
    '--app-color-text-secondary': scheme.textSecondary,
    '--app-color-text-tertiary': scheme.textTertiary,
    '--app-color-focus-ring': scheme.focusRing,
    '--app-color-glow': scheme.glow,
  };
}

/**
 * Colours that differ between the schemes. Components read Mantine's variables, our own CSS reads
 * the `--app-*` ones; both switch with `data-mantine-color-scheme` on <html>.
 */
export const cssVariablesResolver: CSSVariablesResolver = () => ({
  variables: {
    '--app-header-height': '3rem',
  },
  dark: schemeVariables(schemes.dark),
  light: schemeVariables(schemes.light),
});
