import { ActionIcon, CopyButton, Group, Stack, Text, Title, Tooltip } from '@mantine/core';
import { IconCheck, IconCopy } from '@tabler/icons-react';
import type { ReactNode } from 'react';

/** A titled block of the card. `aside` sits next to the title (e.g. the lock of the content). */
export function Section({ title, aside, children }: { title: string; aside?: ReactNode; children: ReactNode }) {
  return (
    <Stack component="section" gap="xs" aria-label={title}>
      <Group gap="xs" wrap="nowrap">
        <Title order={3} size="md" fw={590}>
          {title}
        </Title>
        {aside}
      </Group>
      {children}
    </Stack>
  );
}

/** A label above its value, for the properties and exchange panels. */
export function Property({ label, children }: { label: string; children: ReactNode }) {
  return (
    <Stack gap={0}>
      <Text size="sm" c="dimmed">
        {label}
      </Text>
      <Text component="div" size="md">
        {children}
      </Text>
    </Stack>
  );
}

/** A value with a button that copies it: tracking numbers and operation ids. */
export function CopyValue({ value, label, mono = false }: { value: string; label: string; mono?: boolean }) {
  return (
    <Group gap="xs" wrap="nowrap" miw={0}>
      <Text component="span" ff={mono ? 'monospace' : undefined} size={mono ? 'sm' : 'md'} miw={0} style={{ overflowWrap: 'anywhere' }}>
        {value}
      </Text>
      <CopyButton value={value} timeout={1500}>
        {({ copied, copy }) => (
          <Tooltip label={copied ? 'Скопировано' : 'Скопировать'} withArrow>
            <ActionIcon
              variant="subtle"
              color={copied ? 'green' : 'gray'}
              size="sm"
              onClick={copy}
              aria-label={copied ? 'Скопировано' : `Скопировать ${label}`}
            >
              {copied ? <IconCheck size="1em" /> : <IconCopy size="1em" />}
            </ActionIcon>
          </Tooltip>
        )}
      </CopyButton>
    </Group>
  );
}
