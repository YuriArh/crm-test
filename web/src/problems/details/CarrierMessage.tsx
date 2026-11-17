import { ActionIcon, Code, CopyButton, Group, ScrollArea, SimpleGrid, Stack, Text, Tooltip } from '@mantine/core';
import { IconCheck, IconCopy } from '@tabler/icons-react';
import { formatInstant, formatPrice } from '@/requests/format';
import type { ProblemMessage } from '@/shared/api';
import { useBusinessTimezone } from '@/shared/system';
import { Property, Section } from '@/shared/ui/parts';
import { resultStatusLabel } from './resultStatus';

/** The carrier record behind a problem: the fields that could be read and the beginning of its raw text. */
export function CarrierMessage({ message }: { message: ProblemMessage }) {
  const timeZone = useBusinessTimezone();
  const fields: [string, string][] = [];
  if (message.status !== null) fields.push(['Статус', resultStatusLabel(message.status)]);
  if (message.trackingNumber !== null) fields.push(['Номер отправления', message.trackingNumber]);
  if (message.price !== null) fields.push(['Стоимость', formatPrice(message.price)]);
  if (message.reason !== null) fields.push(['Причина отказа', message.reason]);
  if (message.occurredAt !== null) fields.push(['Время у перевозчика', formatInstant(message.occurredAt, timeZone)]);
  if (message.sequence !== null) fields.push(['Порядковый номер', String(message.sequence)]);
  if (message.feedSeq !== null) fields.push(['Номер в ленте', String(message.feedSeq)]);
  if (message.messageId !== null) fields.push(['Идентификатор сообщения', message.messageId]);

  return (
    <Section title="Сообщение перевозчика">
      <Stack gap="sm">
        {fields.length > 0 && (
          <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="sm" verticalSpacing="xs">
            {fields.map(([label, value]) => (
              <Property key={label} label={label}>
                <Text component="span" style={{ overflowWrap: 'anywhere' }}>
                  {value}
                </Text>
              </Property>
            ))}
          </SimpleGrid>
        )}
        <Stack gap={4}>
          <Group justify="space-between" wrap="nowrap">
            <Text size="sm" c="dimmed">
              Исходный текст
            </Text>
            <CopyButton value={message.payloadExcerpt} timeout={1500}>
              {({ copied, copy }) => (
                <Tooltip label={copied ? 'Скопировано' : 'Скопировать'} withArrow>
                  <ActionIcon
                    variant="subtle"
                    color={copied ? 'green' : 'gray'}
                    size="sm"
                    onClick={copy}
                    aria-label={copied ? 'Скопировано' : 'Скопировать исходный текст'}
                  >
                    {copied ? <IconCheck size="1em" /> : <IconCopy size="1em" />}
                  </ActionIcon>
                </Tooltip>
              )}
            </CopyButton>
          </Group>
          <ScrollArea.Autosize mah="var(--app-excerpt-max-height)" type="auto">
            <Code block style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
              {message.payloadExcerpt}
            </Code>
          </ScrollArea.Autosize>
        </Stack>
      </Stack>
    </Section>
  );
}
