package com.acme.dispatch.problem;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.acme.dispatch.request.BusinessResult;
import com.acme.dispatch.request.BusinessStatus;
import com.acme.dispatch.request.PersonRef;

/**
 * A problem as the API shows it ({@code contracts/dispatch-api.yaml}, {@code ExchangeProblem}).
 *
 * @param request       {@code null} for a record that names no known request
 * @param message       the record behind a record problem; {@code null} for delivery problems
 * @param currentResult the request's result now, for a result conflict; otherwise {@code null}
 * @param resolvedBy    the operator who closed it; {@code null} while open or when the system closed it
 * @param actions       decisions an operator may take now
 */
public record ExchangeProblem(
        UUID id,
        ProblemKind kind,
        ProblemStatus status,
        String details,
        Instant openedAt,
        Instant resolvedAt,
        RequestRef request,
        UUID operationId,
        Message message,
        CurrentResult currentResult,
        PersonRef resolvedBy,
        ProblemDecision decision,
        String comment,
        List<ProblemDecision> actions) {

    public ExchangeProblem {
        actions = List.copyOf(actions);
    }

    public record RequestRef(UUID id, String number) {
    }

    /** What could be read from the record (each field {@code null} if not) and the start of its raw text. */
    public record Message(String messageId, Long feedSeq, String status, Integer sequence, String trackingNumber,
                          BigDecimal price, String reason, Instant occurredAt, String payloadExcerpt) {
    }

    public record CurrentResult(BusinessStatus businessStatus, BusinessResult result) {
    }
}
