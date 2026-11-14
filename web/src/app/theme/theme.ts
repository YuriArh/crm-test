import {
  ActionIcon,
  Alert,
  AppShell,
  Button,
  createTheme,
  Input,
  Paper,
  rem,
  type MantineSize,
} from '@mantine/core';
import classes from './components.module.css';
import { accentScale, darkScale, fonts, grayScale } from './tokens';

/** Control heights on the 4 px grid: compact like Linear, 32 px for the default size. */
const CONTROL_HEIGHTS: Record<MantineSize, number> = { xs: 24, sm: 32, md: 36, lg: 40, xl: 44 };
const CONTROL_FONT_SIZES: Record<MantineSize, number> = { xs: 12, sm: 13, md: 14, lg: 15, xl: 16 };

function controlSize(size: unknown): MantineSize {
  return typeof size === 'string' && size in CONTROL_HEIGHTS ? (size as MantineSize) : 'sm';
}

export const theme = createTheme({
  colors: {
    dark: darkScale,
    gray: grayScale,
    accent: accentScale,
  },
  primaryColor: 'accent',
  primaryShade: 6,
  autoContrast: true,
  white: '#ffffff',
  black: grayScale[9],

  fontFamily: fonts.sans,
  fontFamilyMonospace: fonts.mono,
  fontSmoothing: true,
  fontSizes: {
    xs: rem(11),
    sm: rem(12),
    md: rem(13),
    lg: rem(14),
    xl: rem(16),
  },
  lineHeights: {
    xs: '1.4',
    sm: '1.45',
    md: '1.5',
    lg: '1.5',
    xl: '1.5',
  },
  fontWeights: {
    regular: '400',
    medium: '510',
    bold: '590',
  },
  headings: {
    fontFamily: fonts.sans,
    fontWeight: '590',
    textWrap: 'balance',
    sizes: {
      h1: { fontSize: rem(28), lineHeight: '1.2' },
      h2: { fontSize: rem(22), lineHeight: '1.25' },
      h3: { fontSize: rem(18), lineHeight: '1.3' },
      h4: { fontSize: rem(15), lineHeight: '1.4' },
      h5: { fontSize: rem(13), lineHeight: '1.45' },
      h6: { fontSize: rem(12), lineHeight: '1.45' },
    },
  },

  spacing: {
    xs: rem(4),
    sm: rem(8),
    md: rem(12),
    lg: rem(16),
    xl: rem(24),
  },
  radius: {
    xs: rem(3),
    sm: rem(4),
    md: rem(6),
    lg: rem(8),
    xl: rem(12),
  },
  defaultRadius: 'md',
  shadows: {
    xs: '0 1px 1px rgba(0, 0, 0, 0.08)',
    sm: '0 1px 2px rgba(0, 0, 0, 0.12)',
    md: '0 4px 12px rgba(0, 0, 0, 0.16)',
    lg: '0 8px 24px rgba(0, 0, 0, 0.24)',
    xl: '0 16px 48px rgba(0, 0, 0, 0.32)',
  },
  cursorType: 'pointer',
  focusRing: 'auto',

  components: {
    Button: Button.extend({
      classNames: { root: classes.button },
      vars: (_theme, props) => {
        const size = controlSize(props.size);
        return {
          root: {
            '--button-height': rem(CONTROL_HEIGHTS[size]),
            '--button-fz': rem(CONTROL_FONT_SIZES[size]),
            '--button-padding-x': rem(size === 'xs' ? 8 : 12),
          },
        };
      },
    }),
    ActionIcon: ActionIcon.extend({
      defaultProps: { variant: 'subtle', color: 'gray' },
    }),
    Input: Input.extend({
      classNames: { input: classes.input },
      vars: (_theme, props) => {
        const size = controlSize(props.size);
        return {
          wrapper: {
            '--input-height': rem(CONTROL_HEIGHTS[size]),
            '--input-fz': rem(CONTROL_FONT_SIZES[size]),
          },
        };
      },
    }),
    InputWrapper: Input.Wrapper.extend({
      classNames: { label: classes.label, error: classes.error },
    }),
    Paper: Paper.extend({
      classNames: { root: classes.paper },
    }),
    Alert: Alert.extend({
      defaultProps: { variant: 'light' },
      classNames: { root: classes.alert },
    }),
    AppShell: AppShell.extend({
      classNames: { header: classes.header },
    }),
  },
});
