package com.acme.dispatch.problem;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.acme.dispatch.auth.CurrentUser;
import com.acme.dispatch.common.BusinessCalendar;
import com.acme.dispatch.common.web.ApiException;
import com.acme.dispatch.common.web.ErrorCode;
import com.acme.dispatch.exchange.SendQueueDao;
import com.acme.dispatch.exchange.inbox.OperatorResults;
import com.acme.dispatch.exchange.reconcile.Reconciler;
import com.acme.dispatch.exchange.reconcile.ReconciliationQueryDao;
import com.acme.dispatch.exchange.reconcile.ReconciliationView;
import com.acme.dispatch.exchange.reconcile.RunType;
import com.acme.dispatch.idempotency.IdempotencyService;
import com.acme.dispatch.idempotency.IdempotentOutcome;
import com.acme.dispatch.idempotency.IdempotentResponse;
import com.acme.dispatch.request.RequestCard;
import com.acme.dispatch.request.RequestQueryService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * An operator's actions on the exchange ({@code docs/specs/operator-problems.md} §2.4). Each runs in one transaction
 * with its {@code Idempotency-Key}; the target of the action is part of the request the key is checked against.
 * Only an operator of the request's service acts: a request outside the caller's scope is {@code 404}, an initiator
 * gets {@code 403}, an action the state does not allow is {@code 409 INVALID_STATE}.
 */
@Service
public class OperatorActions {

    static final String RETRY_OPERATION = "RETRY_DELIVERY";
    static final String RESOLVE_OPERATION = "RESOLVE_PROBLEM";
    static final String RECONCILE_OPERATION = "RECONCILE";

    private static final Logger log = LoggerFactory.getLogger(OperatorActions.class);

    private final RequestQueryService cards;
    private final SendQueueDao queue;
    private final ProblemDao problems;
    private final ProblemQueryDao problemQueries;
    private final OperatorResults results;
    private final IdempotencyService idempotency;
    private final BusinessCalendar calendar;
    private final Reconciler reconciler;
    private final ReconciliationQueryDao reconciliations;

    public OperatorActions(RequestQueryService cards, SendQueueDao queue, ProblemDao problems,
                           ProblemQueryDao problemQueries, OperatorResults results, IdempotencyService idempotency,
                           BusinessCalendar calendar, Reconciler reconciler, ReconciliationQueryDao reconciliations) {
        this.cards = cards;
        this.queue = queue;
        this.problems = problems;
        this.problemQueries = problemQueries;
        this.results = results;
        this.idempotency = idempotency;
        this.calendar = calendar;
        this.reconciler = reconciler;
        this.reconciliations = reconciliations;
    }

    /**
     * Decides on a problem: «принять результат перевозчика» ({@code APPLY_CARRIER}) or «оставить наш»
     * ({@code KEEP_OURS}) for a result conflict, «Разобрано» ({@code ACKNOWLEDGED}) for a record problem. The
     * decision must be one of the problem's actions. {@code APPLY_CARRIER} locks the operation row first, then closes
     * the problem — the lock order of the exchange (operation before problem). Closing is conditional on the problem
     * being open, so of two concurrent decisions one wins and the other is {@code 409}. Returns the problem after it.
     */
    @Transactional
    public IdempotentOutcome<ExchangeProblem> resolve(CurrentUser user, UUID problemId, String idempotencyKey,
                                                      ResolveProblemBody body) {
        ProblemController.requireOperator(user);
        ExchangeProblem problem = problemQueries.find(problemId, user)
                .orElseThrow(() -> ApiException.notFound("Problem " + problemId + " not found"));
        Map<String, Object> request = Map.of("problemId", problemId, "decision", body.decision(),
                "comment", body.comment());
        return idempotency.execute(user.id(), idempotencyKey, RESOLVE_OPERATION, request, ExchangeProblem.class,
                () -> {
                    if (!problem.actions().contains(body.decision())) {
                        throw invalidDecision(problem, body.decision());
                    }
                    Instant now = calendar.now();
                    if (body.decision() == ProblemDecision.APPLY_CARRIER) {
                        results.applyChosen(problems.recordOf(problemId).orElseThrow(), now);
                    }
                    if (!problems.closeById(problemId, body.decision(), user.id(), body.comment(), now)) {
                        throw invalidDecision(problem, body.decision());
                    }
                    log.info("Operator {} resolved problem {} {} of request {}: {}", user.username(), problemId,
                            problem.kind(), problem.request() == null ? "-" : problem.request().number(),
                            body.decision());
                    return new IdempotentResponse<>(200, problemQueries.find(problemId, user).orElseThrow(),
                            problemId);
                });
    }

    /**
     * «Сверить сейчас» ({@code docs/specs/reconciliation.md} §2.7): asks the carrier about the request's operation
     * and acts on the outcome, like the background does. Any state; an unavailable carrier is an outcome too. A repeat
     * with the same key returns the recorded check without asking the carrier again.
     */
    @Transactional
    public IdempotentOutcome<ReconciliationView> reconcile(CurrentUser user, UUID requestId, String idempotencyKey) {
        RequestCard card = cards.getCard(requestId, user);
        ProblemController.requireOperator(user);
        return idempotency.execute(user.id(), idempotencyKey, RECONCILE_OPERATION, Map.of("requestId", requestId),
                ReconciliationView.class, () -> {
                    Reconciler.Checked checked = reconciler.checkInTransaction(requestId, RunType.MANUAL, user.id());
                    log.info("Operator {} reconciled request {} (operation {}): {}", user.username(), card.number(),
                            card.operationId(), checked.outcome());
                    return new IdempotentResponse<>(200,
                            reconciliations.find(checked.reconciliationId()).orElseThrow(), requestId);
                });
    }

    private static ApiException invalidDecision(ExchangeProblem problem, ProblemDecision decision) {
        return new ApiException(ErrorCode.INVALID_STATE, "Problem " + problem.id() + " (" + problem.kind()
                + ") does not accept " + decision + " now; it is " + problem.status() + ", allowed: "
                + problem.actions());
    }

    /**
     * «Повторить отправку»: a new budget of retries, due now, with the same operationId (the carrier executes one
     * operation at most once). Closes an open {@code DELIVERY_FAILED} as {@code RETRIED}; {@code DELIVERY_UNKNOWN}
     * stays open until the carrier confirms receipt. Returns the card after the retry.
     */
    @Transactional
    public IdempotentOutcome<RequestCard> retry(CurrentUser user, UUID requestId, String idempotencyKey) {
        RequestCard card = cards.getCard(requestId, user);
        ProblemController.requireOperator(user);
        return idempotency.execute(user.id(), idempotencyKey, RETRY_OPERATION, Map.of("requestId", requestId),
                RequestCard.class, () -> {
                    Instant now = calendar.now();
                    if (!queue.retryByOperator(requestId, now)) {
                        throw new ApiException(ErrorCode.INVALID_STATE, "Delivery of request " + card.number()
                                + " is " + card.deliveryStatus() + "; only FAILED or UNKNOWN can be retried, or an "
                                + "acknowledged one the carrier lost while it has no result");
                    }
                    problems.closeByOperator(requestId, ProblemKind.DELIVERY_FAILED, ProblemDecision.RETRIED,
                            user.id(), null, now);
                    problems.closeByOperator(requestId, ProblemKind.EXTERNAL_MISSING, ProblemDecision.RETRIED,
                            user.id(), null, now);
                    log.info("Operator {} retried delivery of request {} (operation {}), was {}", user.username(),
                            card.number(), card.operationId(), card.deliveryStatus());
                    return new IdempotentResponse<>(200, cards.getCard(requestId, user), requestId);
                });
    }
}
