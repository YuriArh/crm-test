package com.acme.dispatch.problem;

import static com.acme.dispatch.common.web.ApiExceptionHandler.IDEMPOTENCY_KEY_HEADER;

import java.util.UUID;

import com.acme.dispatch.auth.CurrentUser;
import com.acme.dispatch.common.web.ApiException;
import com.acme.dispatch.idempotency.IdempotentOutcome;
import com.acme.dispatch.request.RequestCard;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** An operator's actions on the exchange ({@code docs/specs/operator-problems.md} §2.4). */
@RestController
class OperatorActionController {

    /** Set on a response that was replayed for a repeated Idempotency-Key. */
    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final OperatorActions actions;

    OperatorActionController(OperatorActions actions) {
        this.actions = actions;
    }

    @PostMapping("/api/requests/{id}/retry")
    ResponseEntity<RequestCard> retry(@PathVariable String id,
                                      @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
                                      @AuthenticationPrincipal CurrentUser user) {
        return respond(actions.retry(user, requestId(id), idempotencyKey));
    }

    @PostMapping("/api/problems/{id}/resolve")
    ResponseEntity<ExchangeProblem> resolve(@PathVariable String id,
                                            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
                                            @Valid @RequestBody ResolveProblemBody body,
                                            @AuthenticationPrincipal CurrentUser user) {
        UUID problemId;
        try {
            problemId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound("Problem " + id + " not found");
        }
        return respond(actions.resolve(user, problemId, idempotencyKey, body));
    }

    private static <T> ResponseEntity<T> respond(IdempotentOutcome<T> outcome) {
        return ResponseEntity.status(outcome.status())
                .header(REPLAYED_HEADER, Boolean.toString(outcome.replayed()))
                .body(outcome.body());
    }

    /** A malformed id cannot name an existing request, so it gets the same 404 as a missing one. */
    private static UUID requestId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound("Request " + id + " not found");
        }
    }
}
