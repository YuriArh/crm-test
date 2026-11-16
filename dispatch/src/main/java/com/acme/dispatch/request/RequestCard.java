package com.acme.dispatch.request;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.acme.dispatch.exchange.DeliveryStatus;
import com.acme.dispatch.problem.ExchangeProblem;
import com.acme.dispatch.problem.RequestAction;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Full view of one request.
 *
 * @param result   {@code null} until the carrier reports a final result
 * @param exchange technical exchange state; {@code null} (and omitted from JSON) for initiators
 * @param actions  operator actions the request's state allows now; empty for initiators
 * @param problems problems of the request, newest first; {@code null} (and omitted from JSON) for initiators
 */
public record RequestCard(
        UUID id,
        String number,
        String cargoDescription,
        BigDecimal weightKg,
        String deliveryAddress,
        LocalDate deadline,
        String comment,
        PersonRef owner,
        PersonRef assignee,
        BusinessStatus businessStatus,
        BusinessResult result,
        DeliveryStatus deliveryStatus,
        UUID operationId,
        boolean overdue,
        Instant createdAt,
        Instant updatedAt,
        long version,
        @JsonInclude(JsonInclude.Include.NON_NULL) ExchangeInfo exchange,
        List<RequestAction> actions,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<ExchangeProblem> problems) {

    public RequestCard {
        actions = List.copyOf(actions);
        problems = problems == null ? null : List.copyOf(problems);
    }

    /** As an operator sees it: with its problems and the actions its state allows. */
    RequestCard forOperator(List<ExchangeProblem> problems, List<RequestAction> actions) {
        return new RequestCard(id, number, cargoDescription, weightKg, deliveryAddress, deadline, comment, owner,
                assignee, businessStatus, result, deliveryStatus, operationId, overdue, createdAt, updatedAt, version,
                exchange, actions, problems);
    }

    /** As an initiator sees it: no technical exchange state, no problems, no actions. */
    RequestCard forInitiator() {
        return new RequestCard(id, number, cargoDescription, weightKg, deliveryAddress, deadline, comment, owner,
                assignee, businessStatus, result, deliveryStatus, operationId, overdue, createdAt, updatedAt, version,
                null, List.of(), null);
    }
}
