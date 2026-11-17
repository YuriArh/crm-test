package com.acme.dispatch.problem;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.acme.dispatch.config.IdGenerator;
import com.acme.dispatch.exchange.DeliveryStatus;
import com.acme.dispatch.exchange.inbox.InboxDao.Decision;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Opens and closes problems ({@code docs/specs/operator-problems.md} §2.3). Called by the exchange inside the
 * transaction of the transition that causes the change, and only after that transition took effect: a problem never
 * disagrees with the state. A refused problem rolls the transition back.
 */
@Service
public class ProblemService {

    private static final Logger log = LoggerFactory.getLogger(ProblemService.class);

    private final ProblemDao problems;
    private final IdGenerator ids;

    public ProblemService(ProblemDao problems, IdGenerator ids) {
        this.problems = problems;
        this.ids = ids;
    }

    /**
     * A delivery transition took effect.
     *
     * @param lastError      the operation's new {@code last_error}: why delivery failed
     * @param unknownOutcome the error of the attempt behind it if that attempt ended with an unknown outcome (timeout,
     *                       interrupted send), else {@code null}: then the carrier may have the request
     */
    public void onDeliveryStatus(UUID requestId, UUID operationId, DeliveryStatus status, String lastError,
                                 String unknownOutcome, Instant now) {
        ProblemRules.DeliveryEffect effect = ProblemRules.forDelivery(status, unknownOutcome != null);
        for (ProblemKind kind : effect.open()) {
            String details = detailsOf(kind == ProblemKind.DELIVERY_UNKNOWN ? unknownOutcome : lastError, status);
            if (problems.open(ids.newId(), kind, requestId, null, details, now)) {
                log.warn("Problem {} opened for request {} (operation {}): {}", kind, requestId, operationId, details);
            }
        }
        if (!effect.close().isEmpty()) {
            int closed = problems.closeBySystem(requestId, effect.close(), now);
            if (closed > 0) {
                log.info("Closed {} delivery problem(s) of request {} (operation {}): delivery {}", closed, requestId,
                        operationId, status);
            }
        }
    }

    /**
     * A received record was decided. Opens its problem if the outcome needs an operator.
     *
     * @param requestId its request, or {@code null} if the record names no known operation
     * @param reason    why: the record's {@code reason}
     */
    public void onRecordOutcome(UUID inboxId, UUID requestId, Decision outcome, String reason, Instant now) {
        Optional<ProblemKind> kind = ProblemRules.forRecord(outcome);
        if (kind.isEmpty()) {
            return;
        }
        String details = reason == null || reason.isBlank() ? "Record is " + outcome : reason;
        if (problems.open(ids.newId(), kind.get(), requestId, inboxId, details, now)) {
            log.warn("Problem {} opened for record {} of request {}: {}", kind.get(), inboxId, requestId, details);
        }
    }

    private static String detailsOf(String details, DeliveryStatus status) {
        return details == null || details.isBlank() ? "Delivery is " + status : details;
    }
}
