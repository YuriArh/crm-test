import { Container, Divider, Grid, Paper, Stack, useMantineTheme } from '@mantine/core';
import { useMediaQuery } from '@mantine/hooks';
import { useSuspenseQuery } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { useCurrentUser } from '@/auth';
import { isCardGone, requestCardQueryOptions } from './api';
import { CardHeader } from './CardHeader';
import { ContentSection } from './ContentSection';
import { AssigneeField, CommentField, SaveActions } from './EditableFields';
import { ExchangePanel } from './ExchangePanel';
import { LeaveGuard } from './LeaveGuard';
import { PropertiesPanel } from './PropertiesPanel';
import { RequestNotFound } from './RequestNotFound';
import { ResultSection } from './ResultSection';
import { useCardEditor } from './useCardEditor';

/**
 * The request card. Desktop: main content on the left, properties (and the operator's exchange block) in
 * a narrow column on the right. Phone: one column — header, content, result, properties, comment,
 * exchange — and the save bar sticks to the bottom of the screen while there are changes.
 */
export function RequestCardPage({ id }: { id: string }) {
  const me = useCurrentUser();
  const theme = useMantineTheme();
  const phone = useMediaQuery(`not all and (min-width: ${theme.breakpoints.sm})`, false, {
    getInitialValueInEffect: false,
  });
  const { data: card, error } = useSuspenseQuery(requestCardQueryOptions(id));
  const editor = useCardEditor(card);

  if (isCardGone(error)) return <RequestNotFound />;

  const header = <CardHeader card={card} />;
  const content = <ContentSection card={card} />;
  const result = <ResultSection card={card} />;
  const comment = <CommentField card={card} editor={editor} />;
  const properties = (
    <PropertiesPanel
      card={card}
      assignee={me.role === 'OPERATOR' ? <AssigneeField card={card} editor={editor} /> : undefined}
    />
  );
  const exchange = card.exchange ? <ExchangePanel card={card} exchange={card.exchange} /> : null;

  return (
    <Container size="lg" px={0}>
      <LeaveGuard dirty={editor.dirty} />
      {phone ? (
        <Stack gap="xl">
          {header}
          {content}
          {result}
          <Panel>{properties}</Panel>
          {comment}
          {exchange && <Panel>{exchange}</Panel>}
          <SaveActions editor={editor} variant="bar" />
        </Stack>
      ) : (
        <Stack gap="lg">
          {header}
          <Grid gap="xl">
            <Grid.Col span={{ base: 12, sm: 8, lg: 9 }}>
              <Stack gap="xl">
                {content}
                {result}
                <Stack gap="sm">
                  {comment}
                  <SaveActions editor={editor} variant="inline" />
                </Stack>
              </Stack>
            </Grid.Col>
            <Grid.Col span={{ base: 12, sm: 4, lg: 3 }}>
              <Panel>
                <Stack gap="md">
                  {properties}
                  {exchange && (
                    <>
                      <Divider />
                      {exchange}
                    </>
                  )}
                </Stack>
              </Panel>
            </Grid.Col>
          </Grid>
        </Stack>
      )}
    </Container>
  );
}

function Panel({ children }: { children: ReactNode }) {
  return (
    <Paper withBorder p="md" bg="var(--app-color-surface)">
      {children}
    </Paper>
  );
}
