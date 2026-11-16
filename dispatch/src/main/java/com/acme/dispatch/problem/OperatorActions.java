package com.acme.dispatch.problem;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.acme.dispatch.auth.CurrentUser;
import com.acme.dispatch.common.BusinessCalendar;
import com.acme.dispatch.common.web.ApiException;
import com.acme.dispatch.common.web.ErrorCode;
import com.acme.dispatch.exchange.SendQueueDao;
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

    private static final Logger log = LoggerFactory.getLogger(OperatorActions.class);

    private final RequestQueryService cards;
    private final SendQueueDao queue;
    private final ProblemDao problems;
    private final IdempotencyService idempotency;
    private final BusinessCalendar calendar;

    public OperatorActions(RequestQueryService cards, SendQueueDao queue, ProblemDao problems,
                           IdempotencyService idempotency, BusinessCalendar calendar) {
        this.cards = cards;
        this.queue = queue;
        this.problems = problems;
        this.idempotency = idempotency;
        this.calendar = calendar;
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
                                + " is " + card.deliveryStatus() + "; only FAILED or UNKNOWN can be retried");
                    }
                    problems.closeByOperator(requestId, ProblemKind.DELIVERY_FAILED, ProblemDecision.RETRIED,
                            user.id(), null, now);
                    log.info("Operator {} retried delivery of request {} (operation {}), was {}", user.username(),
                            card.number(), card.operationId(), card.deliveryStatus());
                    return new IdempotentResponse<>(200, cards.getCard(requestId, user), requestId);
                });
    }
}
