import type { MantineColorsTuple } from '@mantine/core';

/**
 * Design tokens of Dispatch, after the look of Linear: near-black neutral surfaces, thin translucent
 * borders, one indigo accent, Inter at a compact 13 px, a 4 px spacing grid and small radii.
 * The Mantine theme (theme.ts) and the CSS variables (cssVariables.ts) are built from these values
 * only; screens use Mantine props and `--app-*` variables, never raw colours.
 */

/** Neutrals for the dark scheme, Mantine's `dark` scale: 0 is the brightest text, 9 the deepest background. */
export const darkScale: MantineColorsTuple = [
  '#f7f8f8', // 0 primary text
  '#d0d6e0', // 1 secondary text
  '#8a8f98', // 2 tertiary text (dimmed)
  '#62666d', // 3 placeholder, disabled text
  '#3e3f44', // 4 strong border
  '#2a2b30', // 5 hover on controls
  '#1f2023', // 6 controls
  '#161719', // 7 raised panels
  '#0f1011', // 8 app background
  '#08090a', // 9 deepest background
];

/** Neutrals for the light scheme, Mantine's `gray` scale: 0 is the lightest surface, 9 the darkest text. */
export const grayScale: MantineColorsTuple = [
  '#fbfbfc', // 0 app background
  '#f4f5f7', // 1 subtle surface, hover
  '#ebecef', // 2 pressed
  '#e0e1e6', // 3 border
  '#c9cbd1', // 4 strong border
  '#9ea1a8', // 5 placeholder
  '#6b6f76', // 6 dimmed text
  '#4f5259', // 7
  '#3c3f45', // 8 secondary text
  '#1b1c1f', // 9 primary text
];

/** The accent, an indigo around #5e6ad2 (shade 6). */
export const accentScale: MantineColorsTuple = [
  '#eef0ff',
  '#dfe2fb',
  '#bec4f4',
  '#9ba3ec',
  '#7e88e5',
  '#6c77df',
  '#5e6ad2',
  '#4f5bc0',
  '#4450a8',
  '#373f8c',
];

/** Status colours, muted to sit on dark surfaces. */
export const statusColors = {
  red: '#eb5757',
  green: '#4cb782',
  yellow: '#f2c94c',
  orange: '#f2994a',
  blue: '#4ea7fc',
} as const;

/** Semantic surface, border and text colours per scheme (exposed as CSS variables). */
export const schemes = {
  dark: {
    body: darkScale[8],
    surface: darkScale[7],
    surfaceRaised: '#1b1c1f',
    control: '#18191b',
    controlHover: darkScale[6],
    border: 'rgba(255, 255, 255, 0.08)',
    borderStrong: 'rgba(255, 255, 255, 0.14)',
    text: darkScale[0],
    textSecondary: darkScale[1],
    textTertiary: darkScale[2],
    placeholder: darkScale[3],
    focusRing: 'rgba(94, 106, 210, 0.5)',
    glow: 'rgba(94, 106, 210, 0.16)',
  },
  light: {
    body: grayScale[0],
    surface: '#ffffff',
    surfaceRaised: '#ffffff',
    control: '#ffffff',
    controlHover: grayScale[1],
    border: '#e6e7eb',
    borderStrong: grayScale[4],
    text: grayScale[9],
    textSecondary: grayScale[8],
    textTertiary: grayScale[6],
    placeholder: grayScale[5],
    focusRing: 'rgba(94, 106, 210, 0.4)',
    glow: 'rgba(94, 106, 210, 0.1)',
  },
} as const;

export const fonts = {
  sans: '"Inter Variable", Inter, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
  mono: '"JetBrains Mono Variable", ui-monospace, SFMono-Regular, Menlo, Consolas, monospace',
} as const;
