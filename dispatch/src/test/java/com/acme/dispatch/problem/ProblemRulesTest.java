package com.acme.dispatch.problem;

import static com.acme.dispatch.problem.ProblemKind.DELIVERY_FAILED;
import static com.acme.dispatch.problem.ProblemKind.DELIVERY_UNKNOWN;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.Set;

import com.acme.dispatch.exchange.DeliveryStatus;
import com.acme.dispatch.exchange.inbox.InboxDao.Decision;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ProblemRulesTest {

    @Test
    void unknownDeliveryOpensDeliveryUnknown() {
        assertThat(ProblemRules.forDelivery(DeliveryStatus.UNKNOWN, true))
                .isEqualTo(new ProblemRules.DeliveryEffect(Set.of(DELIVERY_UNKNOWN), Set.of()));
    }

    @Test
    void failedDeliveryOpensDeliveryFailed() {
        assertThat(ProblemRules.forDelivery(DeliveryStatus.FAILED, false))
                .isEqualTo(new ProblemRules.DeliveryEffect(Set.of(DELIVERY_FAILED), Set.of()));
    }

    @Test
    void failedAfterAnAttemptOfUnknownOutcomeIsAlsoAmbiguous() {
        assertThat(ProblemRules.forDelivery(DeliveryStatus.FAILED, true))
                .isEqualTo(new ProblemRules.DeliveryEffect(Set.of(DELIVERY_FAILED, DELIVERY_UNKNOWN), Set.of()));
    }

    @Test
    void acknowledgedClosesBoth() {
        assertThat(ProblemRules.forDelivery(DeliveryStatus.ACKNOWLEDGED, false))
                .isEqualTo(new ProblemRules.DeliveryEffect(Set.of(), Set.of(DELIVERY_UNKNOWN, DELIVERY_FAILED)));
    }

    @ParameterizedTest
    @EnumSource(value = DeliveryStatus.class, names = {"PENDING", "SENDING", "RETRY_WAIT"})
    void otherStatusesChangeNothing(DeliveryStatus status) {
        assertThat(ProblemRules.forDelivery(status, false))
                .isEqualTo(new ProblemRules.DeliveryEffect(Set.of(), Set.of()));
    }

    @Test
    void recordOutcomesOpenTheirProblems() {
        assertThat(ProblemRules.forRecord(Decision.INVALID)).contains(ProblemKind.INVALID_MESSAGE);
        assertThat(ProblemRules.forRecord(Decision.FAILED)).contains(ProblemKind.MESSAGE_FAILED);
        assertThat(ProblemRules.forRecord(Decision.CONFLICT)).contains(ProblemKind.RESULT_CONFLICT);
        assertThat(ProblemRules.forRecord(Decision.APPLIED)).isEqualTo(Optional.empty());
        assertThat(ProblemRules.forRecord(Decision.STALE)).isEqualTo(Optional.empty());
    }

    @Test
    void conflictWithAFinalRecordCanBeAppliedOrKept() {
        assertThat(ProblemRules.decisions(ProblemKind.RESULT_CONFLICT, ProblemStatus.OPEN, true, true))
                .containsExactly(ProblemDecision.APPLY_CARRIER, ProblemDecision.KEEP_OURS);
    }

    @Test
    void conflictWithAnInProgressRecordCanOnlyBeKept() {
        assertThat(ProblemRules.decisions(ProblemKind.RESULT_CONFLICT, ProblemStatus.OPEN, false, true))
                .containsExactly(ProblemDecision.KEEP_OURS);
    }

    @Test
    void messageProblemsAreAcknowledged() {
        assertThat(ProblemRules.decisions(ProblemKind.INVALID_MESSAGE, ProblemStatus.OPEN, false, false))
                .containsExactly(ProblemDecision.ACKNOWLEDGED);
        assertThat(ProblemRules.decisions(ProblemKind.MESSAGE_FAILED, ProblemStatus.OPEN, false, false))
                .containsExactly(ProblemDecision.ACKNOWLEDGED);
    }

    @ParameterizedTest
    @EnumSource(value = ProblemKind.class, names = {"DELIVERY_UNKNOWN", "DELIVERY_FAILED"})
    void deliveryProblemsAreNotResolvedDirectly(ProblemKind kind) {
        assertThat(ProblemRules.decisions(kind, ProblemStatus.OPEN, false, false)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(ProblemKind.class)
    void resolvedProblemHasNoDecisions(ProblemKind kind) {
        assertThat(ProblemRules.decisions(kind, ProblemStatus.RESOLVED, true, true)).isEmpty();
    }

    /** reconciliation.md §2.7: with a result ours stays (the carrier lost it); without one the way out is a retry. */
    @Test
    void externalMissingDecisions() {
        assertThat(ProblemRules.decisions(ProblemKind.EXTERNAL_MISSING, ProblemStatus.OPEN, false, true))
                .containsExactly(ProblemDecision.KEEP_OURS);
        assertThat(ProblemRules.decisions(ProblemKind.EXTERNAL_MISSING, ProblemStatus.OPEN, false, false)).isEmpty();
    }

    /** reconciliation.md §2.7: the carrier's side can be applied only while it is a final result. */
    @Test
    void reconciliationConflictDecisions() {
        assertThat(ProblemRules.decisions(ProblemKind.RECONCILIATION_CONFLICT, ProblemStatus.OPEN, true, true))
                .containsExactly(ProblemDecision.APPLY_CARRIER, ProblemDecision.KEEP_OURS);
        assertThat(ProblemRules.decisions(ProblemKind.RECONCILIATION_CONFLICT, ProblemStatus.OPEN, false, true))
                .containsExactly(ProblemDecision.KEEP_OURS);
    }

    @ParameterizedTest
    @EnumSource(value = DeliveryStatus.class, names = {"FAILED", "UNKNOWN"})
    void failedAndUnknownDeliveryCanBeRetried(DeliveryStatus status) {
        assertThat(ProblemRules.requestActions(status, Set.of(), false))
                .containsExactly(RequestAction.RETRY, RequestAction.RECONCILE);
    }

    @ParameterizedTest
    @EnumSource(value = DeliveryStatus.class, names = {"PENDING", "SENDING", "RETRY_WAIT", "ACKNOWLEDGED"})
    void otherDeliveryCanOnlyBeReconciled(DeliveryStatus status) {
        assertThat(ProblemRules.requestActions(status, Set.of(), false)).containsExactly(RequestAction.RECONCILE);
    }

    @Test
    void lostAcknowledgedOperationWithoutResultCanBeRetried() {
        assertThat(ProblemRules.requestActions(DeliveryStatus.ACKNOWLEDGED, Set.of(ProblemKind.EXTERNAL_MISSING),
                false)).containsExactly(RequestAction.RETRY, RequestAction.RECONCILE);
    }

    @Test
    void lostAcknowledgedOperationWithResultIsNotRetried() {
        assertThat(ProblemRules.requestActions(DeliveryStatus.ACKNOWLEDGED, Set.of(ProblemKind.EXTERNAL_MISSING),
                true)).containsExactly(RequestAction.RECONCILE);
    }
}
