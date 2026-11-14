import { Stack, Text } from '@mantine/core';
import type { ExchangeInfo, RequestCard } from '@/shared/api';
import { useBusinessTimezone } from '@/shared/system';
import { formatInstant } from '../format';
import { CopyValue, Property, Section } from './parts';

const ACKNOWLEDGED_VIA: Record<NonNullable<ExchangeInfo['acknowledgedVia']>, string> = {
  RESPONSE: 'ответ на отправку',
  RESULT: 'по пришедшему результату',
  RECONCILIATION: 'сверка',
};

/** The technical state of the exchange with the carrier. Only operators get `exchange` from the server. */
export function ExchangePanel({ card, exchange }: { card: RequestCard; exchange: ExchangeInfo }) {
  const timeZone = useBusinessTimezone();

  return (
    <Section title="Обмен с перевозчиком">
      <Stack gap="sm">
        <Property label="Попыток отправки">{exchange.attempts}</Property>
        {exchange.nextAttemptAt && (
          <Property label="Следующая попытка">{formatInstant(exchange.nextAttemptAt, timeZone)}</Property>
        )}
        {exchange.lastError && (
          <Property label="Последняя ошибка">
            <Text c="red" size="sm" style={{ overflowWrap: 'anywhere' }}>
              {exchange.lastError}
            </Text>
          </Property>
        )}
        <Property label="Получение подтверждено">
          {exchange.acknowledgedAt ? (
            <>
              {formatInstant(exchange.acknowledgedAt, timeZone)}
              {exchange.acknowledgedVia && (
                <Text component="span" c="dimmed">
                  {' '}
                  · {ACKNOWLEDGED_VIA[exchange.acknowledgedVia]}
                </Text>
              )}
            </>
          ) : (
            <Text component="span" c="dimmed">
              Ещё нет
            </Text>
          )}
        </Property>
        <Property label="Идентификатор операции">
          <CopyValue value={card.operationId} label="идентификатор операции" mono />
        </Property>
      </Stack>
    </Section>
  );
}
