package com.acme.dispatch.exchange.inbox;

/**
 * Applicability of a valid result to an operation ({@code docs/dispatch-design.md} §5, D03). Pure function: the
 * caller supplies the operation's current state under a row lock.
 */
public final class ResultRules {

    public enum Decision {
        /** Newer than anything applied: update the request. */
        APPLIED,
        /** Not newer than what is applied, same content: no effect. */
        STALE,
        /** Contradicts what is applied: no effect, needs an operator. */
        CONFLICT
    }

    private ResultRules() {
    }

    /**
     * @param lastAppliedSequence    highest sequence applied to the operation (0 = none)
     * @param appliedContentSameSeq  content of the applied message with the same sequence, or {@code null}
     * @param requestIsFinal         whether the request already has a final result
     */
    public static Decision decide(int lastAppliedSequence, String appliedContentSameSeq, boolean requestIsFinal,
                                  ResultMessage message) {
        if (appliedContentSameSeq != null && !appliedContentSameSeq.equals(message.content())) {
            return Decision.CONFLICT;
        }
        if (message.sequence() <= lastAppliedSequence) {
            return Decision.STALE;
        }
        if (requestIsFinal) {
            // A final result is never replaced automatically, whatever arrives later.
            return Decision.CONFLICT;
        }
        return Decision.APPLIED;
    }
}
