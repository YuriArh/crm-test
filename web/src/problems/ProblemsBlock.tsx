import { Accordion, Alert, Group, Stack, Text } from '@mantine/core';
import type { ReactNode } from 'react';
import type { ExchangeProblem, ProblemDecision } from '@/shared/api';
import { useBusinessTimezone } from '@/shared/system';
import { Section } from '@/shared/ui/parts';
import { ProblemDetails } from './details/ProblemDetails';
import { resolvedText } from './details/ResolvedLine';
import { kindView } from './labels';

type Props = {
  problems: ExchangeProblem[];
  /** Decisions on a problem; without it the notices show no decision buttons. */
  onDecide?: (problem: ExchangeProblem, decision: ProblemDecision) => void;
  /** Request actions shown in a problem's notice (the card offers «Повторить отправку» on delivery problems). */
  actionsFor?: (problem: ExchangeProblem) => ReactNode;
};

/**
 * The problems of one request (web-problems.md §7.1): each open one as a notice coloured by its kind, with what
 * dispatch allows to do about it; closed ones folded under their count, one line each.
 */
export function ProblemsBlock({ problems, onDecide, actionsFor }: Props) {
  const timeZone = useBusinessTimezone();
  if (problems.length === 0) return null;
  const open = problems.filter((problem) => problem.status === 'OPEN');
  const closed = problems.filter((problem) => problem.status !== 'OPEN');

  return (
    <Section title="Проблемы">
      <Stack gap="sm">
        {open.map((problem) => (
          <Alert key={problem.id} color={kindView(problem.kind).color} variant="light" data-kind={problem.kind}>
            <ProblemDetails
              problem={problem}
              showRequest={false}
              onDecide={onDecide ? (decision) => onDecide(problem, decision) : undefined}
              actions={actionsFor?.(problem)}
            />
          </Alert>
        ))}
        {closed.length > 0 && (
          <Accordion variant="separated" chevronPosition="left">
            <Accordion.Item value="closed">
              <Accordion.Control>Закрытые проблемы ({closed.length})</Accordion.Control>
              <Accordion.Panel>
                <Accordion variant="default" chevronPosition="left">
                  {closed.map((problem) => (
                    <Accordion.Item key={problem.id} value={problem.id}>
                      <Accordion.Control>
                        <Group gap="xs" wrap="wrap">
                          <Text size="sm" fw={510}>
                            {kindView(problem.kind).label}
                          </Text>
                          <Text size="sm" c="dimmed">
                            {resolvedText(problem, timeZone)}
                          </Text>
                        </Group>
                      </Accordion.Control>
                      <Accordion.Panel>
                        <ProblemDetails problem={problem} showRequest={false} />
                      </Accordion.Panel>
                    </Accordion.Item>
                  ))}
                </Accordion>
              </Accordion.Panel>
            </Accordion.Item>
          </Accordion>
        )}
      </Stack>
    </Section>
  );
}
