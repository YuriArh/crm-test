import { useState, type ReactNode } from 'react';
import type { ExchangeProblem, ProblemDecision } from '@/shared/api';
import { ResolveModal } from './ResolveModal';

/**
 * The decision dialog of a screen that shows problems (the card, the problems panel): `open` is the `onDecide` of
 * the problems, `dialog` goes anywhere in the screen. Each opening starts with an empty comment and new keys.
 */
export function useDecisionDialog(): {
  open: (problem: ExchangeProblem, decision: ProblemDecision) => void;
  dialog: ReactNode;
} {
  const [current, setCurrent] = useState<{ problem: ExchangeProblem; decision: ProblemDecision; n: number } | null>(
    null,
  );
  return {
    open: (problem, decision) => setCurrent((previous) => ({ problem, decision, n: (previous?.n ?? 0) + 1 })),
    dialog: current && (
      <ResolveModal
        key={current.n}
        problem={current.problem}
        decision={current.decision}
        onClose={() => setCurrent(null)}
      />
    ),
  };
}
