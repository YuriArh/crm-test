import { ActionIcon, Tooltip, useComputedColorScheme, useMantineColorScheme } from '@mantine/core';
import { IconMoon, IconSun } from '@tabler/icons-react';

/** Switches between the dark (default) and the light scheme; Mantine keeps the choice in localStorage. */
export function ColorSchemeToggle() {
  const { setColorScheme } = useMantineColorScheme();
  const computed = useComputedColorScheme('dark');
  const next = computed === 'dark' ? 'light' : 'dark';
  const label = next === 'light' ? 'Светлая тема' : 'Тёмная тема';

  return (
    <Tooltip label={label} openDelay={400}>
      <ActionIcon size="md" aria-label={label} onClick={() => setColorScheme(next)}>
        {next === 'light' ? <IconSun size={16} stroke={1.6} /> : <IconMoon size={16} stroke={1.6} />}
      </ActionIcon>
    </Tooltip>
  );
}
