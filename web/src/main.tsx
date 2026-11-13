import '@mantine/core/styles.css';
import '@mantine/dates/styles.css';
import '@mantine/notifications/styles.css';
import 'dayjs/locale/ru';

import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

import { createQueryClient } from './api/queryClient';
import { App } from './App';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App queryClient={createQueryClient()} />
  </StrictMode>,
);
