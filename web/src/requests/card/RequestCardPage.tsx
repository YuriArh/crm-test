import { Container, Divider, Grid, Paper, Stack } from '@mantine/core';
import { useSuspenseQuery } from '@tanstack/react-query';
import { isCardGone, requestCardQueryOptions } from './api';
import { CardHeader } from './CardHeader';
import { ContentSection } from './ContentSection';
import { CommentField, SaveActions } from './EditableFields';
import { ExchangePanel } from './ExchangePanel';
import { PropertiesPanel } from './PropertiesPanel';
import { RequestNotFound } from './RequestNotFound';
import { ResultSection } from './ResultSection';
import { useCardEditor } from './useCardEditor';

/** The request card: main content on the left, properties (and the operator's exchange block) on the right. */
export function RequestCardPage({ id }: { id: string }) {
  const { data: card, error } = useSuspenseQuery(requestCardQueryOptions(id));
  const editor = useCardEditor(card);

  if (isCardGone(error)) return <RequestNotFound />;

  return (
    <Container size="lg" px={0}>
      <Stack gap="lg">
        <CardHeader card={card} />
        <Grid gap="xl">
          <Grid.Col span={{ base: 12, sm: 8, lg: 9 }}>
            <Stack gap="xl">
              <ContentSection card={card} />
              <ResultSection card={card} />
              <Stack gap="sm">
                <CommentField editor={editor} />
                <SaveActions editor={editor} />
              </Stack>
            </Stack>
          </Grid.Col>
          <Grid.Col span={{ base: 12, sm: 4, lg: 3 }}>
            <Paper withBorder p="md" bg="var(--app-color-surface)">
              <Stack gap="md">
                <PropertiesPanel card={card} />
                {card.exchange && (
                  <>
                    <Divider />
                    <ExchangePanel card={card} exchange={card.exchange} />
                  </>
                )}
              </Stack>
            </Paper>
          </Grid.Col>
        </Grid>
      </Stack>
    </Container>
  );
}
