import { createContext } from 'react';

import type { Me } from '../api/types';

export const CurrentUserContext = createContext<Me | null>(null);
